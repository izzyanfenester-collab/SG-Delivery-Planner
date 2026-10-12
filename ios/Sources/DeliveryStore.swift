import Foundation

struct DeliveryStop: Codable, Identifiable, Hashable {
    var id = UUID()
    let postalCode: String
    var status: Status = .pending
    var deliveredAt: Date? = nil
    enum Status: String, Codable, CaseIterable { case pending, delivered, hold, skipped }
}
struct DeliveryRoute: Codable, Identifiable {
    var id = UUID()
    var createdAt = Date()
    var title: String = "Singapore Delivery"
    var startLocation = "Woodlands Checkpoint"
    var stops: [DeliveryStop]
}
@MainActor
final class DeliveryStore: ObservableObject {
    @Published var routes: [DeliveryRoute] = [] { didSet { save() } }
    private let key = "sgdelivery.routes.v1"
    init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let value = try? JSONDecoder().decode([DeliveryRoute].self, from: data) { routes = value }
    }
    static func parse(_ input: String) -> [String] {
        let pattern = #"(?<!\d)\d{6}(?!\d)"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        let ns = input as NSString
        var seen = Set<String>()
        return regex.matches(in: input, range: NSRange(location: 0, length: ns.length))
            .map { ns.substring(with: $0.range) }
            .filter { seen.insert($0).inserted }
            .prefix(50).map { $0 }
    }
    func create(from input: String) -> Int {
        let postals = Self.parse(input)
        if !postals.isEmpty { routes.insert(DeliveryRoute(stops: postals.map { DeliveryStop(postalCode: $0) }), at: 0) }
        return postals.count
    }
    func setStatus(routeID: UUID, stopID: UUID, status: DeliveryStop.Status) {
        guard let r = routes.firstIndex(where: {$0.id == routeID}),
              let s = routes[r].stops.firstIndex(where: {$0.id == stopID}) else { return }
        routes[r].stops[s].status = status
        routes[r].stops[s].deliveredAt = status == .delivered ? Date() : nil
    }
    func exportData() -> Data? { try? JSONEncoder().encode(routes) }
    func restore(from data: Data) throws { routes = try JSONDecoder().decode([DeliveryRoute].self, from: data) }
    private func save() { if let data = exportData() { UserDefaults.standard.set(data, forKey: key) } }
}
