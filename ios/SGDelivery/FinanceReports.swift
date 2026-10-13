import Foundation

/// Same money conventions as Android (SGD, two-decimal tax, positive SGD→MYR rate).
enum MoneyCalculator {
    static func amount(_ value: String) -> Decimal? {
        let input = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard input.range(of: #"^[0-9]{1,9}(?:\.[0-9]{1,2})?$"#,
                          options: .regularExpression) != nil,
              let amount = Decimal(string: input, locale: Locale(identifier: "en_US_POSIX")),
              amount >= 0 else { return nil }
        return amount
    }
    static func positiveRate(_ value: String) -> Decimal? {
        let input = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard input.range(of: #"^[0-9]{1,3}(?:\.[0-9]{1,4})?$"#,
                          options: .regularExpression) != nil,
              let rate = Decimal(string: input, locale: Locale(identifier: "en_US_POSIX")),
              rate > 0, rate <= 100 else { return nil }
        return rate
    }
    static func display(_ value: Decimal) -> String {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.minimumFractionDigits = 2
        f.maximumFractionDigits = 2
        f.roundingMode = .halfUp
        f.usesGroupingSeparator = false
        return f.string(from: NSDecimalNumber(decimal: value)) ?? "0.00"
    }
    static func rateDisplay(_ value: Decimal) -> String { display(value) }
    static func taxMYR(tax: Decimal, rate: Decimal) -> Decimal {
        var result = tax * rate
        var rounded = Decimal()
        NSDecimalRound(&rounded, &result, 2, .plain)
        return rounded
    }
}

struct DeliveryReportSummary {
    let route: DeliveryRoute
    var total: Int { route.orders.count }
    var delivered: Int { route.orders.filter { $0.status == .delivered }.count }
    var onHold: Int { route.orders.filter { $0.status == .hold }.count }
    var skipped: Int { route.orders.filter { $0.status == .skipped }.count }
    var pending: Int { total - delivered - onHold - skipped }
    var percent: String { String(format: "%.1f%%", route.successRate) }
    var cash: Decimal { MoneyCalculator.amount(route.cashOnHand ?? "") ?? 0 }
    var tax: Decimal { MoneyCalculator.amount(route.tax ?? "") ?? 0 }
    var rate: Decimal { MoneyCalculator.positiveRate(route.exchangeRate ?? "") ?? Decimal(string:"3.60")! }
    var collectedCOD: Decimal {
        route.orders.filter { $0.status == .delivered && $0.payment.uppercased() == "COD" }
            .compactMap { MoneyCalculator.amount($0.amount) }.reduce(Decimal.zero, +)
    }
    var fields: [(String,String)] {
        [
            ("Date", SGFormat.date(route.date)),
            ("Start", SGFormat.date(route.startTime)),
            ("Finish", route.returnETA.map(SGFormat.date) ?? "—"),
            ("Total Parcel", String(total)),
            ("Delivered", String(delivered)),
            ("On Hold", String(onHold)),
            ("Skipped", String(skipped)),
            ("Pending", String(pending)),
            ("Success Rate", percent),
            ("Total KM", String(format: "%.1f KM", route.totalKm ?? 0)),
            ("Cash on Hand", "SGD \(MoneyCalculator.display(cash))"),
            ("Delivered COD Total", "SGD \(MoneyCalculator.display(collectedCOD))"),
            ("Tax", "SGD \(MoneyCalculator.display(tax)) (RM \(MoneyCalculator.display(MoneyCalculator.taxMYR(tax: tax, rate: rate))))"),
            ("Rate SGD→MYR", MoneyCalculator.display(rate)),
            ("Remark", route.remark ?? "")
        ]
    }
    var printable: String {
        "Delivery Report Summary\n" + fields.map { "\($0.0): \($0.1)" }.joined(separator: "\n")
    }
}
