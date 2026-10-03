import Foundation
import CoreLocation

struct PostalInput: Codable { let valid: [String]; let invalid: [String]; let duplicates: Int }
struct Place: Codable {
    let postal: String; let lat: Double; let lon: Double; let block: String; let area: String; let address: String
    var coordinate: CLLocationCoordinate2D { .init(latitude: lat, longitude: lon) }
}
struct StartLocation: Codable, Equatable {
    var type = "WOODLANDS"; var lat = 1.4464; var lon = 103.7696
    var label = "Woodlands Checkpoint"; var address = "21 Woodlands Crossing"; var postal = "738203"
    var coordinate: CLLocationCoordinate2D { .init(latitude: lat, longitude: lon) }
    var place: Place { .init(postal: postal, lat: lat, lon: lon, block: label, area: "", address: address) }
    var valid: Bool { lat.isFinite && lon.isFinite && (-90...90).contains(lat) && (-180...180).contains(lon) && !label.trimmingCharacters(in: .whitespaces).isEmpty }
}
struct Leg: Codable { let km: Double; let baseSeconds: Double; let bufferSeconds: Double; var plannedSeconds: Double { baseSeconds + bufferSeconds } }
struct Money: Codable { var amount = "0.00" }
struct Stop: Codable {
    let place: Place; let leg: Leg; let arrival: Int64; let leave: Int64; let cumulativeKm: Double
    var status = "PENDING"; var completedAt: Int64?; var holdReason: String?; var holdNote: String?; var reviewedAt: Int64?
    var statusLabel: String { ["DELIVERED": "Delivered", "ON_HOLD": "On Hold", "SKIPPED": "Skipped", "PENDING": "Pending"][status] ?? "Pending" }
}
struct Route: Codable, Identifiable {
    let id: String; let created: Int64; let start: Int64; let serviceMinutes: Int; let mode: String
    var stops: [Stop]; let returnLeg: Leg; let returned: Int64; let geometry: [[Double]]; let startLocation: StartLocation
    var current: Int; var actualCompletion: Int64?; var reviewedFinishedAt: Int64?
    var cashOnHand: Money; var tax: Money; var summarySavedAt: Int64?
    var coordinates: [CLLocationCoordinate2D] { geometry.compactMap { $0.count >= 2 ? .init(latitude: $0[1], longitude: $0[0]) : nil } }
}
struct DeliverySummary: Codable {
    let totalParcel: Int; let delivered: Int; let onHold: Int; let skipped: Int; let pending: Int; let successRate: Double
    let start: Int64; let finish: Int64; let totalRouteSeconds: Int64; let totalKm: Double; let returned: Int64
    let cashOnHand: Money; let tax: Money
}
struct RoadMatrix: Codable { let places: [Place]; let meters: [[Double]]; let seconds: [[Double]]; let mode: String }
struct Tour: Codable { let order: [Int]; let places: [Place]; let legs: [Leg] }
struct ScheduleInput: Codable { let id: String; let created: Int64; let start: Int64; let serviceMinutes: Int; let mode: String; let tour: Tour; let geometry: [[Double]]; let startLocation: StartLocation }
struct ReviewInput: Codable { let route: Route; let action: String; let now: Int64; let holdReason: String?; let holdNote: String? }
struct MoneyInput: Codable { let route: Route; let cashOnHand: String; let tax: String; let now: Int64 }
struct Settings: Codable {
    var serviceMinutes = 8; var mode = "Normal Traffic"; var endpoint = "https://router.project-osrm.org"
}
struct SavedState: Codable {
    var version = 1; var routes: [Route] = []; var activeID: String?; var home: StartLocation?
    var selectedLocation = StartLocation(); var postalText = ""; var departure = Date(); var settings = Settings()
}
extension Date { var epoch: Int64 { Int64(timeIntervalSince1970) } }
enum SingaporeTime {
    static func text(_ epoch: Int64, date: Bool = true) -> String {
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_SG")
        formatter.timeZone = TimeZone(identifier: "Asia/Singapore")
        formatter.dateFormat = date ? "dd MMM yyyy, HH:mm" : "HH:mm"
        return formatter.string(from: Date(timeIntervalSince1970: Double(epoch)))
    }
    static func day(_ epoch: Int64) -> String {
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Asia/Singapore"); formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: Date(timeIntervalSince1970: Double(epoch)))
    }
}
struct DeliveryError: LocalizedError { let message: String; var errorDescription: String? { message } }
