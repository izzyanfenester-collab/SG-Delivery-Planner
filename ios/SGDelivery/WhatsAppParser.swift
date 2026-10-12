import Foundation

enum WhatsAppParser {
    private static func match(_ pattern: String, in value: String, group: Int = 0) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]),
              let m = re.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)),
              group < m.numberOfRanges, let range = Range(m.range(at: group), in: value) else { return nil }
        return String(value[range])
    }

    static func phone(_ value: String) -> String {
        let digits = value.filter(\.isNumber)
        if digits.range(of: #"^[89][0-9]{7}$"#, options: .regularExpression) != nil { return "+65" + digits }
        if digits.range(of: #"^65[89][0-9]{7}$"#, options: .regularExpression) != nil { return "+" + digits }
        return ""
    }

    private static func field(_ line: String) -> (String, String)? {
        let clean = line.replacingOccurrences(of: #"^[^\p{L}\p{N}]+"#, with: "", options: .regularExpression)
        guard let colon = clean.firstIndex(of: ":") else { return nil }
        let key = clean[..<colon].lowercased().replacingOccurrences(of: ".", with: "")
            .trimmingCharacters(in: .whitespaces).replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        let value = String(clean[clean.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
        let names: [String: String] = [
            "nama":"name","name":"name","customer":"name","customer name":"name",
            "alamat":"address","address":"address",
            "no telefon":"phone","notelefon":"phone","phone":"phone","phone no":"phone",
            "telephone":"phone","whatsapp":"phone","whatsapp no":"phone",
            "total":"amount","amount":"amount",
            "order":"end","item":"end","tarikh order":"end","date":"end","fb":"end","page":"end"
        ]
        guard let type = names[key] else { return nil }
        return (type,value)
    }

    private static func isHeading(_ line: String) -> Bool {
        if field(line) != nil { return false }
        return match(#"^\s*[^\p{L}\p{N}]*ORDER\b|\bINVOICE\s*:"# , in: line) != nil
    }

    static func parse(_ input: String) -> ImportResult {
        let text = input.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return ImportResult(orders: [], duplicates: 0, failed: 0)
        }
        let manual = text.range(of: #"^[\s0-9,;]+$"#, options: .regularExpression) != nil
        if manual {
            var seen = Set<String>(), orders: [DeliveryOrder] = [], duplicates = 0
            let re = try! NSRegularExpression(pattern: #"(?<!\d)[0-9]{6}(?!\d)"#)
            for m in re.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
                guard let range = Range(m.range, in: text) else { continue }
                let postal = String(text[range])
                if !seen.insert(postal).inserted { duplicates += 1 }
                else { orders.append(DeliveryOrder(postal: postal)) }
            }
            return ImportResult(orders: orders, duplicates: duplicates, failed: 0)
        }

        var blocks: [[String]] = [], current: [String] = [], seenFields = Set<String>()
        func flush() {
            if current.contains(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) { blocks.append(current) }
            current = []; seenFields = []
        }
        for raw in text.components(separatedBy: "\n") {
            let header = match(#"^\s*\[[0-9]{1,4}[/.-][0-9]{1,2}[^\]\n]*\]\s*[^:\n]+:\s*(.*)$"#, in: raw, group: 1)
                ?? match(#"^\s*[0-9]{1,4}[/.-][0-9]{1,2}(?:[/.-][0-9]{2,4})?,\s*[0-9]{1,2}:[0-9]{2}[^\n]*? - [^:\n]+:\s*(.*)$"#, in: raw, group: 1)
            if header != nil { flush() }
            let line = header ?? raw
            let type = field(line)?.0
            if (isHeading(line) && !seenFields.isEmpty) ||
                (type != nil && ["name","address","amount"].contains(type!) && seenFields.contains(type!)) { flush() }
            current.append(line)
            if let type { seenFields.insert(type) }
        }
        flush()

        var orders: [DeliveryOrder] = [], duplicates = 0, failed = 0
        var seenMessages = Set<String>()
        for lines in blocks {
            let original = lines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
            if !seenMessages.insert(original).inserted { duplicates += 1; continue }
            var values: [String: String] = [:], active: String?, parts: [String] = []
            func save() {
                if let active, values[active] == nil {
                    values[active] = parts.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
                }
                parts = []
            }
            for line in lines {
                if let (type,value) = field(line) { save(); active = type; parts = [value] }
                else if isHeading(line) { save(); active = nil }
                else if active != nil { parts.append(line) }
            }
            save()
            let address = values["address"] ?? ""
            let cleanAddress = address.replacingOccurrences(of: #"https?://\S+"#, with: " ", options: .regularExpression)
                .replacingOccurrences(of: #"\b[0-9]{1,4}[/.-][0-9]{1,2}[/.-][0-9]{2,4}\b"#, with: " ", options: .regularExpression)
            guard let postal = match(#"(?:\bSingapore|\bS['’]pore|\bSG|\bS)\s*([0-9]{6})(?![0-9])"#, in: cleanAddress, group: 1)
                ?? match(#"(?<!\d)[0-9]{6}(?!\d)"#, in: cleanAddress) else { failed += 1; continue }
            let rawPhone = values["phone"] ?? ""
            let number = match(#"wa\.me/\+?([0-9]+)"#, in: rawPhone, group: 1) ?? rawPhone.components(separatedBy: "\n").first ?? ""
            let amount = match(#"^(?:\$\s*|SGD\s*)?([0-9]+(?:\.[0-9]{1,2})?)(?![0-9.])"#, in: values["amount"] ?? "", group: 1) ?? ""
            let payment = values["amount"] ?? ""
            orders.append(DeliveryOrder(postal: postal,
                name: values["name"]?.components(separatedBy: "\n").first ?? "",
                phone: phone(number), address: address, amount: amount,
                payment: payment.localizedCaseInsensitiveContains("PAYNOW") ? "PayNow" :
                    (payment.localizedCaseInsensitiveContains("COD") ? "COD" : ""),
                originalMessage: original))
        }
        return ImportResult(orders: orders, duplicates: duplicates, failed: failed)
    }
}
