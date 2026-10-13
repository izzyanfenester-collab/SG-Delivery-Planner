import Foundation

@main
struct IOSCoreSmokeTests {
    static func main() throws {
        func check(_ condition: @autoclosure () -> Bool, _ message: String) {
            precondition(condition(), "iOS parity failed: " + message)
        }

        let orderA = """
        🔥 ORDER TUDUNG 🔥
        Total: $65 (COD)
        Nama: Samaniah
        Alamat:
        Blk 171 Woodlands Singapore 730171
        WhatsApp: https://wa.me/+6590123456
        Item: Blue
        """
        let orderB = """
        🔥 ORDER KAIN 🔥
        Total: $75 (PAYNOW)
        Nama: Nurijah
        Alamat:
        Blk 171 Woodlands Singapore 730171
        WhatsApp: https://wa.me/+6590234567
        Item: Red
        """
        let result = WhatsAppParser.parse(orderA + "\n\n" + orderB)
        check(result.orders.count == 2, "Two different customers must both import")
        check(result.failed == 0, "Both valid orders should pass")
        check(result.orders[0].name == "Samaniah", "Customer A name")
        check(result.orders[1].name == "Nurijah", "Customer B name")
        check(result.orders[0].postal == "730171", "Customer A postal")
        check(result.orders[1].postal == "730171", "Customer B postal")
        check(result.orders[0].payment == "COD", "COD captured")
        check(result.orders[1].payment == "PayNow", "PayNow captured")
        check(result.duplicatePostalGroups.count == 1, "One duplicate postal group")
        check(result.duplicateParcelCount == 1, "One extra parcel at same postal")

        let malformed = WhatsAppParser.parse("""
            Nama: Sharipa
            Total: $55 (COD)
            Alamat:
            Blk 303B Anchorvale Link Singapore 73017
            WhatsApp: https://wa.me/+6590199999
            Item: Brown
            """)
        check(malformed.orders.isEmpty, "Malformed postal must not be silently accepted")
        check(malformed.failed == 1, "Failed import counted")
        check(malformed.failedOrders.count == 1, "Runner sees rejected order detail")
        check(malformed.failedOrders[0].customerName == "Sharipa", "Rejected customer retained")
        check(malformed.failedOrders[0].originalMessage.contains("Brown"), "Original item preserved")
        check(malformed.failedOrders[0].reason.contains("73017"), "Suspect postal shown")

        let corrected = WhatsAppParser.parse(
            malformed.failedOrders[0].originalMessage.replacingOccurrences(of: "73017", with: "730171"))
        check(corrected.orders.count == 1 && corrected.failed == 0, "Corrected order re-imports")

        let manual = WhatsAppParser.parse("730171\n730171\n520220")
        check(manual.orders.count == 2, "Manual postal duplicates removed")
        check(manual.duplicates == 1, "Manual duplicate count")
        let many = (1...50).map { n in
            "ORDER \(n)\nTotal: $55 (COD)\nNama: Runner \(n)\nAlamat:\nBlk \(n) Singapore 730171\nItem: Red"
        }.joined(separator: "\n\n")
        let batch = WhatsAppParser.parse(many)
        check(batch.orders.count == 50, "50 WhatsApp customers retained")
        check(batch.duplicateParcelCount == 49, "50 customers at same postal preserved")

        let matrix: [[Double]] = [[0, 5, 10], [7, 0, 2], [2, 1, 0]]
        let sequence = try RoutePlanner.optimize(matrix)
        check(sequence.sorted() == [1,2], "Route optimizer must visit all locations")
        let light = RoutePlanner.trafficBuffer(km: 5, seconds: 600, mode: "Light Traffic")
        let normal = RoutePlanner.trafficBuffer(km: 5, seconds: 600, mode: "Normal")
        let heavy = RoutePlanner.trafficBuffer(km: 5, seconds: 600, mode: "Heavy Traffic")
        check(light < normal && normal < heavy, "Traffic buffers ordered correctly")
        // Android V1.4.2 Send message: use this order only, exact imported text,
        // two-decimal COD amount, Singapore ETA, +10 min, and safe WhatsApp links.
        var sgCalendar = Calendar(identifier: .gregorian)
        sgCalendar.timeZone = SGFormat.singapore
        let planned = sgCalendar.date(from: DateComponents(
            year: 2026, month: 10, day: 13, hour: 19, minute: 21))!
        let updated = sgCalendar.date(from: DateComponents(
            year: 2026, month: 10, day: 13, hour: 19, minute: 10))!
        var customerA = result.orders[0]
        customerA.amount = "75"
        customerA.arrival = planned
        customerA.updatedArrival = updated
        let draftA = DeliveryWhatsApp.draft(for: customerA)!
        check(draftA.contains("COD Amount: S$75.00"), "COD uses selected customer's price")
        check(draftA.contains("ETA : 7.10pm - 7.20pm"), "Updated ETA preferred over planned ETA")
        check(draftA.contains(customerA.originalMessage.trimmingCharacters(in: .whitespacesAndNewlines)),
              "Exact original customer order remains in draft")
        check(draftA.contains("please have the exact cash ready"), "Cash reminder present")
        check(DeliveryWhatsApp.phoneDigits(customerA.phone) == "6590123456", "SG phone is normalized")

        var customerB = result.orders[1]
        customerB.arrival = planned
        let draftB = DeliveryWhatsApp.draft(for: customerB)!
        check(draftB.contains("COD Amount: S$75.00"), "Second customer's own amount")
        check(draftB.contains(customerB.originalMessage), "Second customer's original message")
        check(!draftB.contains(customerA.originalMessage), "Same postcode never swaps orders")
        check(draftB.contains("ETA : 7.21pm - 7.31pm"), "Planned ETA fallback")
        let links = DeliveryWhatsApp.urls(for: customerA)!
        check(links.app.scheme == "whatsapp", "Direct native WhatsApp scheme")
        check(links.web.host == "wa.me", "HTTPS WhatsApp fallback")
        check(URLComponents(url: links.app, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "text" })?.value == draftA,
              "Native app link preserves emoji and newlines")
        check(URLComponents(url: links.web, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "text" })?.value == draftA,
              "Fallback link preserves the full exact message")

        var night = customerA
        night.updatedArrival = sgCalendar.date(from: DateComponents(
            year: 2026, month: 10, day: 13, hour: 23, minute: 55))
        check(DeliveryWhatsApp.etaWindow(for: night) == "11.55pm - 12.05am",
              "10-minute window crosses midnight without truncation")
        var incomplete = customerA
        incomplete.originalMessage = ""
        check(DeliveryWhatsApp.draft(for: incomplete) == nil, "Never invent missing original order")
        incomplete = customerA
        incomplete.amount = ""
        check(DeliveryWhatsApp.draft(for: incomplete) == nil, "Never invent missing parcel price")
        incomplete = customerA
        incomplete.phone = "12345"
        check(DeliveryWhatsApp.urls(for: incomplete) == nil, "Reject invalid phone number")
        incomplete = customerA
        incomplete.arrival = nil
        incomplete.updatedArrival = nil
        check(DeliveryWhatsApp.draft(for: incomplete) == nil, "Never invent ETA")
        let persisted = try JSONEncoder().encode(customerA)
        let restored = try JSONDecoder().decode(DeliveryOrder.self, from: persisted)
        check(restored.updatedArrival == updated, "Updated ETA survives JSON archive")
        var oldJSON = try JSONSerialization.jsonObject(with: persisted) as! [String: Any]
        oldJSON.removeValue(forKey: "updatedArrival")
        let older = try JSONDecoder().decode(DeliveryOrder.self,
            from: JSONSerialization.data(withJSONObject: oldJSON))
        check(older.updatedArrival == nil, "Older iOS backup remains compatible")

        print("SG Delivery native iOS core smoke tests PASSED.")
    }
}
