import Foundation
import SwiftUI

@MainActor
final class DeliveryStore: ObservableObject {
    @Published var routes: [DeliveryRoute] = [] { didSet { save() } }
    @Published var selectedRouteID: UUID? { didSet { save() } }
    @Published var draftOrders: [DeliveryOrder] = [] { didSet { save() } }
    @Published var postalInput = "" { didSet { save() } }
    @Published var importText = ""
    @Published var isPlanning = false
    @Published var errorMessage: String?
    @Published var noticeMessage: String?
    @Published var failedRoutePostals: [PostalFailure] = []
    @Published var showFailedPostalReview = false
    @Published var deliveryDate = Date()
    @Published var deliveryTime = Date()
    @Published var depotPostal = "738203" { didSet { UserDefaults.standard.set(depotPostal, forKey: "sg.depot") } }
    @Published var minutesPerStop = 8 { didSet { UserDefaults.standard.set(minutesPerStop, forKey: "sg.service") } }
    @Published var trafficMode = "Normal" { didSet { UserDefaults.standard.set(trafficMode, forKey: "sg.traffic") } }
    @Published var appearance = "Dark" { didSet { UserDefaults.standard.set(appearance, forKey: "sg.appearance") } }
    @Published var roadServer = "https://router.project-osrm.org" { didSet { UserDefaults.standard.set(roadServer, forKey: "sg.server") } }
    @Published var defaultNavigation = "Ask every time" { didSet { UserDefaults.standard.set(defaultNavigation, forKey: "sg.navigation") } }

    private var restoring = false
    private struct Archive: Codable {
        var version: Int
        var routes: [DeliveryRoute]
        var selectedRouteID: UUID?
        var draftOrders: [DeliveryOrder]?
        var postalInput: String?
    }
    private var url: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("sg-delivery-v3.json")
    }

    init() {
        restoring = true
        let settings = UserDefaults.standard
        depotPostal = settings.string(forKey: "sg.depot") ?? "738203"
        minutesPerStop = settings.object(forKey: "sg.service") as? Int ?? 8
        trafficMode = settings.string(forKey: "sg.traffic") ?? "Normal"
        appearance = settings.string(forKey: "sg.appearance") ?? "Dark"
        roadServer = settings.string(forKey: "sg.server") ?? "https://router.project-osrm.org"
        defaultNavigation = settings.string(forKey: "sg.navigation") ?? "Ask every time"
        load()
        restoring = false
    }
    var activeIndex: Int? { routes.firstIndex(where: { $0.id == selectedRouteID }) }
    var active: DeliveryRoute? {
        guard let i = activeIndex else { return nil }
        return routes[i]
    }
    var draft: [DeliveryOrder] {
        if draftOrders.isEmpty { return parseManualPostals(postalInput) }
        return draftOrders
    }
    var duplicateParcelCount: Int { draft.count - Set(draft.map(\.postal)).count }

    private func load() {
        guard let data = try? Data(contentsOf: url),
              let archive = try? JSONDecoder().decode(Archive.self, from: data),
              archive.version == 3 else { return }
        routes = archive.routes
        selectedRouteID = archive.selectedRouteID
        draftOrders = archive.draftOrders ?? []
        postalInput = archive.postalInput ?? ""
    }
    private func save() {
        guard !restoring else { return }
        let archive = Archive(version: 3, routes: routes, selectedRouteID: selectedRouteID,
                              draftOrders: draftOrders, postalInput: postalInput)
        do {
            let data = try JSONEncoder().encode(archive)
            try data.write(to: url, options: .atomic)
        } catch {
            errorMessage = "Could not save delivery records: \(error.localizedDescription)"
        }
    }
    func setPostalText(_ value: String) {
        draftOrders = []
        postalInput = value
        failedRoutePostals = []
    }
    func clearDraft() {
        draftOrders = []
        postalInput = ""
        failedRoutePostals = []
        noticeMessage = nil
    }

    func importPreview() -> ImportResult { WhatsAppParser.parse(importText) }

    func addImportedOrders(_ imported: [DeliveryOrder]) {
        guard !imported.isEmpty else { errorMessage = "Select at least one order"; return }
        guard draft.count + imported.count <= 50 else {
            errorMessage = "Maximum 50 parcels. Select fewer orders."
            return
        }
        let existing = draft
        let combined = existing + imported
        guard Set(combined.map(\.id)).count == combined.count else {
            errorMessage = "Duplicate parcel IDs detected"; return
        }
        draftOrders = combined
        postalInput = combined.map(\.postal).joined(separator: "\n")
        failedRoutePostals = []
        noticeMessage = "\(imported.count) orders imported. Duplicate postal codes are preserved as separate parcels."
    }

    func correctDraftOrder(orderID: UUID, editedText: String) -> String? {
        let all = draft
        guard let index = all.firstIndex(where: { $0.id == orderID }) else {
            return "Order no longer exists in the draft."
        }
        let old = all[index], source = editedText.trimmingCharacters(in: .whitespacesAndNewlines)
        var corrected = old
        if old.originalMessage.isEmpty {
            guard source.range(of: #"^[0-9]{6}$"#, options: .regularExpression) != nil else {
                return "Enter one six-digit postal code."
            }
            corrected.postal = source
        } else {
            let result = WhatsAppParser.parse(source)
            guard result.orders.count == 1, result.failed == 0 else {
                return result.failedOrders.first?.reason ?? "Keep exactly one complete WhatsApp order."
            }
            let fresh = result.orders[0]
            corrected.postal = fresh.postal
            corrected.name = fresh.name
            corrected.phone = fresh.phone
            corrected.address = fresh.address
            corrected.amount = fresh.amount
            corrected.payment = fresh.payment
            corrected.originalMessage = fresh.originalMessage
        }
        var updated = all
        updated[index] = corrected
        draftOrders = updated
        postalInput = updated.map(\.postal).joined(separator: "\n")
        failedRoutePostals.removeAll { failure in !updated.contains(where: { $0.postal == failure.postal }) }
        // Recheck all codes against OneMap; a syntactically valid postal is not yet verified.
        noticeMessage = "Updated \(corrected.name.isEmpty ? corrected.postal : corrected.name). Tap Retry Optimize to verify all locations."
        return nil
    }

    func plan() async -> Bool {
        guard !isPlanning else { return false }
        let orders = draft
        guard (1...50).contains(orders.count) else {
            errorMessage = "Enter 1–50 Singapore delivery orders."; return false
        }
        guard orders.allSatisfy({ $0.postal.range(of: #"^[0-9]{6}$"#, options: .regularExpression) != nil }) else {
            errorMessage = "One or more postal codes are not six digits."; return false
        }
        isPlanning = true
        errorMessage = nil
        failedRoutePostals = []
        defer { isPlanning = false }
        do {
            let route = try await RoutePlanner.plan(orders, date: deliveryDate,
                time: deliveryTime, minutes: minutesPerStop, startPostal: depotPostal,
                trafficMode: trafficMode, server: roadServer)
            routes.insert(route, at: 0)
            selectedRouteID = route.id
            showFailedPostalReview = false
            noticeMessage = "Optimized \(route.orders.count) delivery orders."
            return true
        } catch let problem as RoutePostalLookupError {
            failedRoutePostals = problem.failures
            showFailedPostalReview = true
            errorMessage = "Could not verify \(problem.failures.count) postal code(s). Review affected orders or retry OneMap."
        } catch {
            errorMessage = error.localizedDescription
        }
        return false
    }

    func review(orderID: UUID, status: DeliveryStatus, reason: String? = nil, note: String? = nil) {
        guard let i = activeIndex, let j = routes[i].orders.firstIndex(where: { $0.id == orderID }) else { return }
        guard routes[i].orders[j].status != .delivered else {
            errorMessage = "Delivered records are locked to protect delivery history"; return
        }
        if status == .hold {
            let allowed = ["Customer not home","No answer","Reschedule","Payment issue","Access issue","Joy buyer","Other"]
            guard let reason, allowed.contains(reason), reason != "Other" || !(note ?? "").trimmingCharacters(in: .whitespaces).isEmpty else {
                errorMessage = "Select a hold reason and enter a note for Other."; return
            }
        }
        routes[i].orders[j].status = status
        routes[i].orders[j].reviewedAt = Date()
        routes[i].orders[j].holdReason = status == .hold ? reason : nil
        routes[i].orders[j].holdNote = status == .hold && reason == "Other" ? String((note ?? "").prefix(160)) : nil
        if status == .delivered { routes[i].orders[j].deliveredAt = Date() }
    }

    private var lastLiveETAPersist: [UUID: Date] = [:]

    /// Called only while active GPS navigation is on screen. Never overwrites completed parcels.
    func updateLiveETA(orderID: UUID, value: Date) {
        guard value.timeIntervalSinceNow > -90 && value.timeIntervalSinceNow < 18_000,
              let i = activeIndex, let j = routes[i].orders.firstIndex(where: { $0.id == orderID }),
              routes[i].orders[j].status != .delivered else { return }
        let previous = routes[i].orders[j].updatedArrival
        let lastSave = lastLiveETAPersist[orderID] ?? .distantPast
        guard previous == nil ||
              (Date().timeIntervalSince(lastSave) >= 20 &&
               abs(value.timeIntervalSince(previous!)) >= 15) else { return }

        // Shift future untouched parcels by the change in target ETA, retaining service intervals.
        let planned = routes[i].orders[j].arrival
        let baseline = previous ?? planned
        let delta = baseline.map { value.timeIntervalSince($0) } ?? 0
        for k in j..<routes[i].orders.count {
            guard routes[i].orders[k].status != .delivered else { continue }
            if k == j { routes[i].orders[k].updatedArrival = value }
            else if let expected = routes[i].orders[k].updatedArrival ?? routes[i].orders[k].arrival {
                routes[i].orders[k].updatedArrival = expected.addingTimeInterval(delta)
            }
        }
        lastLiveETAPersist[orderID] = Date()
    }

    func updateRouteFinance(cash: String, tax: String, rate: String, remark: String) -> String? {
        guard let i = activeIndex else { return "Select a route before saving the report." }
        guard let cashValue = MoneyCalculator.amount(cash),
              let taxValue = MoneyCalculator.amount(tax),
              let exchange = MoneyCalculator.positiveRate(rate) else {
            return "Enter valid non-negative SGD cash/tax and a positive exchange rate."
        }
        routes[i].cashOnHand = MoneyCalculator.display(cashValue)
        routes[i].tax = MoneyCalculator.display(taxValue)
        routes[i].exchangeRate = MoneyCalculator.rateDisplay(exchange)
        routes[i].remark = String(remark.prefix(500))
        noticeMessage = "Delivery cash and tax saved."
        return nil
    }

    func saveProof(orderID: UUID, data: Data, payment: Bool) {
        guard data.count <= 1_000_000, let i = activeIndex,
              let j = routes[i].orders.firstIndex(where: { $0.id == orderID }) else {
            errorMessage = "Photo too large; select a smaller image"; return
        }
        let value = data.base64EncodedString()
        if payment { routes[i].orders[j].proofPayment = value }
        else { routes[i].orders[j].proofDelivery = value }
    }

    func exportExcel() -> URL? {
        guard let route = active else { errorMessage = "No route selected."; return nil }
        do { return try DeliveryExcel.create(route: route) }
        catch { errorMessage = "Excel export failed: \(error.localizedDescription)"; return nil }
    }

    func exportAndroidBackup() -> URL? {
        do { return try AndroidBackupZIP.exportArchive(routes) }
        catch { errorMessage = "Android backup failed: \(error.localizedDescription)"; return nil }
    }

    /// Imports missing route IDs without silently overwriting local iPhone history or photos.
    func importAndroidBackup(from url: URL) throws -> (restored: Int, skipped: Int, missingProofs: Int) {
        let backup = try AndroidBackupZIP.importArchive(url)
        let incoming = backup.routes
        let currentIDs = Set(routes.map(\.id))
        let newRoutes = incoming.filter { !currentIDs.contains($0.id) }
        guard routes.count + newRoutes.count <= 2000 else {
            throw DeliveryError.invalid("Too many routes in combined history.")
        }
        if !newRoutes.isEmpty {
            restoring = true
            routes = newRoutes + routes
            if selectedRouteID == nil { selectedRouteID = newRoutes.first?.id }
            restoring = false
            save()
        }
        return (newRoutes.count, incoming.count - newRoutes.count, backup.missingProofs)
    }

    func exportJSON() -> URL? {
        do {
            let data = try JSONEncoder().encode(Archive(
                version: 3, routes: routes, selectedRouteID: selectedRouteID,
                draftOrders: draftOrders, postalInput: postalInput))
            let destination = FileManager.default.temporaryDirectory
                .appendingPathComponent("SG-Delivery-iOS-Backup.json")
            try data.write(to: destination, options: .atomic)
            return destination
        } catch { errorMessage = error.localizedDescription; return nil }
    }

    func exportCSV() -> URL? {
        guard let route = active else { errorMessage = "No route to export"; return nil }
        func escaped(_ text: String) -> String { "\"" + text.replacingOccurrences(of: "\"", with: "\"\"") + "\"" }
        var rows = ["No,Postal,Customer,Phone,Address,Amount SGD,Payment,Status,ETA Singapore,Hold Reason"]
        for (i,order) in route.orders.enumerated() {
            rows.append([
                String(i + 1),order.postal,order.name,order.phone,order.address,
                order.amount,order.payment,order.status.rawValue,
                order.arrival.map(SGFormat.date) ?? "",order.holdReason ?? ""
            ].map(escaped).joined(separator: ","))
        }
        do {
            let destination = FileManager.default.temporaryDirectory
                .appendingPathComponent("SG-Delivery-Report.csv")
            let data = Data(("\u{FEFF}" + rows.joined(separator: "\r\n")).utf8)
            try data.write(to: destination, options: .atomic)
            return destination
        } catch { errorMessage = error.localizedDescription; return nil }
    }

    func restoreJSON(_ data: Data) throws {
        guard data.count <= 60_000_000 else { throw DeliveryError.invalid("Backup exceeds 60 MB") }
        let archive = try JSONDecoder().decode(Archive.self, from: data)
        guard archive.version == 3, archive.routes.count <= 2000,
              archive.routes.allSatisfy({ $0.orders.count <= 50 &&
                $0.orders.allSatisfy({ $0.postal.range(of: #"^[0-9]{6}$"#, options: .regularExpression) != nil }) }) else {
            throw DeliveryError.invalid("Unsupported or invalid iOS backup")
        }
        guard archive.selectedRouteID == nil || archive.routes.contains(where: { $0.id == archive.selectedRouteID }) else {
            throw DeliveryError.invalid("Backup has invalid selected route")
        }
        restoring = true
        routes = archive.routes
        selectedRouteID = archive.selectedRouteID
        draftOrders = archive.draftOrders ?? []
        postalInput = archive.postalInput ?? ""
        restoring = false
        save()
    }
}

func parseManualPostals(_ text: String) -> [DeliveryOrder] {
    let tokens = text.split(whereSeparator: { $0.isWhitespace || $0 == "," || $0 == ";" })
    // Android manual input removes repeated postal entries; WhatsApp imports preserve separate customers.
    var used = Set<String>()
    return tokens.map(String.init)
        .filter { $0.range(of: #"^[0-9]{6}$"#, options: .regularExpression) != nil }
        .filter { used.insert($0).inserted }
        .map { postal in DeliveryOrder(id: UUID(uuidString: "00000000-0000-4000-8000-000000\(postal)")!, postal: postal) }
}
