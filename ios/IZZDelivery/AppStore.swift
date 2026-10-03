import SwiftUI
import MapKit

@MainActor final class AppStore: ObservableObject {
    @Published private(set) var state = SavedState()
    @Published var error: String?
    @Published var progress: String?
    @Published var notice: String?
    @Published var tab = 0
    private var storage: ProtectedStorage?
    private var storageReady = false
    private let service = RouteService()
    private var planning: Task<Void, Never>?
    init(storage: ProtectedStorage? = nil) {
        do {
            let store = try storage ?? ProtectedStorage(); self.storage = store
            state = try store.load("state.json", as: SavedState.self) ?? SavedState()
            guard state.version == 1 else { throw DeliveryError(message: "This saved data needs a newer IZZ Delivery version.") }
            storageReady = true
        } catch { self.error = "Saved data could not be opened. Unlock the device and reopen IZZ Delivery. Your saved file has been preserved." }
    }
    var route: Route? { state.routes.first { $0.id == state.activeID } }
    var isPlanning: Bool { progress != nil }
    /// Persist before publishing so a failed save never pretends progress or money was saved.
    @discardableResult func update(_ change: (inout SavedState) -> Void) -> Bool {
        guard storageReady, let storage else { error = "Saved data is unavailable. Reopen IZZ Delivery after unlocking your device."; return false }
        var next = state; change(&next)
        do { try storage.save(next, name: "state.json"); state = next; return true }
        catch { error = "Changes could not be saved securely. Unlock your device and check available storage, then try again."; return false }
    }
    @discardableResult func save(_ route: Route) -> Bool {
        update { state in
            if let i = state.routes.firstIndex(where: { $0.id == route.id }) { state.routes[i] = route }
            else { state.routes.insert(route, at: 0) }
            state.activeID = route.id
        }
    }
    func select(_ route: Route) { if update({ $0.activeID = route.id }) { tab = 1 } }
    func optimize() {
        guard !isPlanning else { return }
        do {
            let parsed = try SharedBridge.parse(state.postalText)
            guard parsed.invalid.isEmpty else { throw DeliveryError(message: "Invalid postal codes: \(parsed.invalid.joined(separator: ", ")). Enter six digits for each postal code.") }
            guard (1...50).contains(parsed.valid.count) else { throw DeliveryError(message: "Plan 1–50 unique postal codes. Split larger lists into separate routes.") }
            let snapshot = state; progress = "Preparing route…"
            planning = Task {
                defer { progress = nil; planning = nil }
                do {
                    let route = try await service.plan(codes: parsed.valid, start: snapshot.departure.epoch, settings: snapshot.settings, location: snapshot.selectedLocation) { [weak self] message in
                        await self?.setProgress(message)
                    }
                    try Task.checkCancellation()
                    if save(route) { tab = 1; if parsed.duplicates > 0 { notice = "Removed \(parsed.duplicates) duplicate postal codes." } }
                } catch is CancellationError { notice = "Planning cancelled. Your entries have been kept." }
                catch { self.error = userMessage(error) }
            }
        } catch { self.error = userMessage(error) }
    }
    private func setProgress(_ message: String) { progress = message }
    func cancelPlanning() { planning?.cancel() }
    func review(_ action: String, reason: String? = nil, note: String? = nil) {
        guard let route else { return }
        do { _ = save(try SharedBridge.review(ReviewInput(route: route, action: action, now: Date().epoch, holdReason: reason, holdNote: note))) }
        catch { self.error = userMessage(error) }
    }
    func revisit(_ index: Int) { guard var route, route.stops.indices.contains(index) else { return }; route.current = index; _ = save(route) }
    @discardableResult func saveSummary(cash: String, tax: String) -> Bool {
        guard let route else { return false }
        do { return save(try SharedBridge.saveMoney(MoneyInput(route: route, cashOnHand: cash, tax: tax, now: Date().epoch))) }
        catch { self.error = userMessage(error); return false }
    }
    func open(_ url: URL) {
        UIApplication.shared.open(url, options: [:]) { [weak self] success in
            if !success { Task { @MainActor in self?.error = "This link could not be opened. Install a browser or Maps and try again." } }
        }
    }
    func navigate(_ place: Place) {
        var c = URLComponents(string: "https://maps.apple.com/")!
        c.queryItems = [.init(name: "daddr", value: "\(place.lat),\(place.lon)"), .init(name: "dirflg", value: "d")]
        if let url = c.url { open(url) }
    }
    func userMessage(_ error: Error) -> String {
        if let error = error as? DeliveryError { return error.message }
        return "Could not process the delivery data. Please try again. Your saved route and entries have been kept."
    }
}
