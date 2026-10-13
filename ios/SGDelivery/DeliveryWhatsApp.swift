import Foundation

/// Android V1.4.2-compatible draft for a *specific* parcel, never a postcode lookup.
/// Building a URL opens an editable WhatsApp draft; sending remains the runner's choice.
enum DeliveryWhatsApp {
    static func phoneDigits(_ input: String) -> String? {
        let digits = input.filter(\.isNumber)
        if digits.range(of: #"^[89][0-9]{7}$"#, options: .regularExpression) != nil {
            return "65" + digits
        }
        if digits.range(of: #"^65[89][0-9]{7}$"#, options: .regularExpression) != nil {
            return digits
        }
        return nil
    }

    static func missingReason(for order: DeliveryOrder) -> String? {
        if phoneDigits(order.phone) == nil {
            return "Customer's Singapore WhatsApp number is missing or invalid. Correct the order first."
        }
        if order.originalMessage.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "Original WhatsApp order is unavailable. Re-import the original customer order to include its exact details."
        }
        if parsedAmount(order.amount) == nil {
            return "Parcel price is missing or invalid. Correct the customer's amount before creating a message."
        }
        if order.updatedArrival == nil && order.arrival == nil {
            return "An ETA is not available. Optimize a route before creating the delivery message."
        }
        return nil
    }

    private static func parsedAmount(_ value: String) -> Decimal? {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: #"^(?:S\$|\$|SGD)\s*"#, with: "", options: [.regularExpression, .caseInsensitive])
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard normalized.range(of: #"^[0-9]+(?:\.[0-9]{1,2})?$"#, options: .regularExpression) != nil,
              let amount = Decimal(string: normalized, locale: Locale(identifier: "en_US_POSIX")),
              amount >= 0 else { return nil }
        return amount
    }

    private static func price(_ amount: Decimal) -> String {
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.numberStyle = .decimal
        formatter.groupingSeparator = ""
        formatter.minimumFractionDigits = 2
        formatter.maximumFractionDigits = 2
        formatter.roundingMode = .halfUp
        return formatter.string(from: NSDecimalNumber(decimal: amount))!
    }

    static func clock(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = SGFormat.singapore
        formatter.dateFormat = "h.mma"
        return formatter.string(from: date).lowercased(with: Locale(identifier: "en_US_POSIX"))
    }

    /// Updated ETA is preferred; planned arrival is the honest fallback.
    static func etaWindow(for order: DeliveryOrder) -> String? {
        guard let start = order.updatedArrival ?? order.arrival else { return nil }
        return "\(clock(start)) - \(clock(start.addingTimeInterval(10 * 60)))"
    }

    static func draft(for order: DeliveryOrder) -> String? {
        guard missingReason(for: order) == nil,
              let amount = parsedAmount(order.amount),
              let eta = etaWindow(for: order) else { return nil }
        let original = order.originalMessage.trimmingCharacters(in: .whitespacesAndNewlines)
        return """
        Hi! I’ll be delivering your COD parcel today.
        COD Amount: S$\(price(amount))
        ETA : \(eta)
        Order Details :
        =================================
        \(original)
        =================================
        To make the delivery process easier and faster, please have the exact cash ready.

        Thank you for your purchase with us! 🙏 We truly appreciate your support. We hope to serve you again for your next order. 😊
        """
    }

    static func urls(for order: DeliveryOrder) -> (app: URL, web: URL)? {
        guard let phone = phoneDigits(order.phone),
              let message = draft(for: order) else { return nil }
        var app = URLComponents()
        app.scheme = "whatsapp"
        app.host = "send"
        app.queryItems = [
            URLQueryItem(name: "phone", value: phone),
            URLQueryItem(name: "text", value: message)
        ]
        var web = URLComponents(string: "https://wa.me/\(phone)")!
        web.queryItems = [URLQueryItem(name: "text", value: message)]
        guard let appURL = app.url, let webURL = web.url else { return nil }
        return (appURL, webURL)
    }
}
