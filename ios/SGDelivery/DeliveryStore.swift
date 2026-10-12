import Foundation
import SwiftUI

@MainActor
final class DeliveryStore: ObservableObject {
    @Published var routes: [DeliveryRoute] = [] { didSet { save() } }
    @Published var selectedRouteID: UUID? { didSet { save() } }
    @Published var importText: String = ""
    @Published var isPlanning = false
    @Published var errorMessage: String?
    @Published var deliveryDate = Date()
    @Published var deliveryTime = Date()
    @Published var depotPostal = "738203"
    @Published var minutesPerStop = 8

    private struct Archive: Codable {
        var version: Int
        var routes: [DeliveryRoute]
        var selectedRouteID: UUID?
    }
    private var url: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("sg-delivery-v3.json")
    }
    init() { load() }
    var activeIndex: Int? { routes.firstIndex(where: { $0.id == selectedRouteID }) }
    var active: DeliveryRoute? { guard let i = activeIndex else { return nil }; return routes[i] }

    private func load() {
        guard let data = try? Data(contentsOf: url),
              let archive = try? JSONDecoder().decode(Archive.self, from: data),
              archive.version == 3 else { return }
        routes = archive.routes
        selectedRouteID = archive.selectedRouteID
    }
    private func save() {
        let archive = Archive(version: 3, routes: routes, selectedRouteID: selectedRouteID)
        do {
            let data = try JSONEncoder().encode(archive)
            try data.write(to: url, options: .atomic)
        } catch { errorMessage = "Could not save delivery records: \(error.localizedDescription)" }
    }
    func importPreview() -> ImportResult { WhatsAppParser.parse(importText) }

    func plan() async {
        let result = importPreview()
        guard !result.orders.isEmpty else { errorMessage = "No valid Singapore postal codes"; return }
        guard result.orders.count <= 50 else { errorMessage = "Maximum 50 orders"; return }
        isPlanning = true
        defer { isPlanning = false }
        do {
            let route = try await RoutePlanner.plan(result.orders, date: deliveryDate,
                time: deliveryTime, minutes: minutesPerStop, startPostal: depotPostal)
            routes.insert(route, at: 0)
            selectedRouteID = route.id
        } catch { errorMessage = error.localizedDescription }
    }

    func review(orderID: UUID, status: DeliveryStatus, reason: String? = nil, note: String? = nil) {
        guard let i = activeIndex, let j = routes[i].orders.firstIndex(where: { $0.id == orderID }) else { return }
        guard routes[i].orders[j].status != .delivered else {
            errorMessage = "Delivered records are locked to protect delivery history"; return
        }
        if status == .hold {
            let allowed = ["Customer not home","No answer","Reschedule","Payment issue","Access issue","Joy buyer","Other"]
            guard let reason, allowed.contains(reason), reason != "Other" || !(note ?? "").trimmingCharacters(in: .whitespaces).isEmpty else {
                errorMessage = "Select a valid hold reason and enter a note for Other"; return
            }
        }
        routes[i].orders[j].status = status
        routes[i].orders[j].reviewedAt = Date()
        routes[i].orders[j].holdReason = status == .hold ? reason : nil
        routes[i].orders[j].holdNote = status == .hold && reason == "Other" ? String((note ?? "").prefix(160)) : nil
        if status == .delivered { routes[i].orders[j].deliveredAt = Date() }
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

    func exportJSON() -> URL? {
        do {
            let data = try JSONEncoder().encode(Archive(version: 3, routes: routes, selectedRouteID: selectedRouteID))
            let destination = FileManager.default.temporaryDirectory.appendingPathComponent("SG-Delivery-iOS-Backup.json")
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
        routes = archive.routes
        selectedRouteID = archive.selectedRouteID
    }
}
