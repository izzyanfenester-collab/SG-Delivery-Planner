import Foundation

struct DeliveryOrder: Identifiable, Codable, Hashable {
    var id: UUID = UUID()
    var postal: String
    var name: String = ""
    var phone: String = ""
    var address: String = ""
    var amount: String = ""
    var payment: String = ""
    var originalMessage: String = ""
    var status: DeliveryStatus = .pending
    var reviewedAt: Date?
    var deliveredAt: Date?
    var holdReason: String?
    var holdNote: String?
    var arrival: Date?
    var leave: Date?
    var latitude: Double?
    var longitude: Double?
    var legKm: Double = 0
    var legSeconds: Double = 0
    var proofDelivery: String?
    var proofPayment: String?
}

enum DeliveryStatus: String, Codable, CaseIterable {
    case pending = "Pending", delivered = "Delivered", hold = "Hold", skipped = "Skipped"
}

struct DeliveryRoute: Identifiable, Codable {
    var id: UUID = UUID()
    var created: Date = Date()
    var date: Date
    var startTime: Date
    var startName: String = "Woodlands Checkpoint"
    var startPostal: String = "738203"
    var depotLatitude: Double?
    var depotLongitude: Double?
    var minutesPerStop: Int = 8
    var orders: [DeliveryOrder]
    var totalKm: Double?
    var returnETA: Date?
    var optimized: Bool = false

    var deliveredCount: Int { orders.filter { $0.status == .delivered }.count }
    var successRate: Double {
        orders.isEmpty ? 0 : Double(deliveredCount) / Double(orders.count) * 100
    }
}

struct ImportResult {
    var orders: [DeliveryOrder]
    var duplicates: Int
    var failed: Int
    var found: Int { orders.count + duplicates + failed }
}

enum DeliveryError: LocalizedError {
    case invalid(String)
    var errorDescription: String? {
        if case .invalid(let message) = self { return message }
        return "Unexpected delivery error"
    }
}

enum SGFormat {
    static let singapore = TimeZone(identifier: "Asia/Singapore")!
    static func date(_ value: Date) -> String {
        let f = DateFormatter()
        f.timeZone = singapore
        f.dateStyle = .medium
        f.timeStyle = .short
        return f.string(from: value)
    }
    static func clock(_ value: Date) -> String {
        let f = DateFormatter()
        f.timeZone = singapore
        f.dateFormat = "HH:mm"
        return f.string(from: value)
    }
}
