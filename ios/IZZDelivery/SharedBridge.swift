import Foundation
import IZZShared

/// All decisions and calculations come from the KMP framework. Swift mirrors its JSON transport only.
enum SharedBridge {
    static func encode<T: Encodable>(_ value: T) throws -> String { String(decoding: try JSONEncoder().encode(value), as: UTF8.self) }
    static func decode<T: Decodable>(_ text: String, as type: T.Type = T.self) throws -> T {
        let data = Data(text.utf8)
        if let error = try? JSONDecoder().decode([String: String].self, from: data), let message = error["error"] { throw DeliveryError(message: message) }
        return try JSONDecoder().decode(type, from: data)
    }
    static func parse(_ raw: String) throws -> PostalInput { try decode(DeliveryEngine().parsePostalCodes(raw: raw)) }
    static func optimize(_ matrix: RoadMatrix) throws -> Tour { try decode(DeliveryEngine().optimize(matrixJson: encode(matrix))) }
    static func schedule(_ input: ScheduleInput) throws -> Route { try decode(DeliveryEngine().createSchedule(inputJson: encode(input))) }
    static func review(_ input: ReviewInput) throws -> Route { try decode(DeliveryEngine().review(inputJson: encode(input))) }
    static func summary(_ route: Route) throws -> DeliverySummary { try decode(DeliveryEngine().deliverySummary(routeJson: encode(route))) }
    static func saveMoney(_ input: MoneyInput) throws -> Route { try decode(DeliveryEngine().saveSummary(inputJson: encode(input))) }
}
