import Foundation
import ZIPFoundation

/// Genuine Office Open XML .xlsx (two sheets), not CSV renamed to XLSX.
/// Text values are inline strings, so customer input never executes Excel formulas.
enum DeliveryExcel {
    private static func escaped(_ raw: String) -> String {
        raw.unicodeScalars
            .filter { $0.value == 9 || $0.value == 10 || $0.value == 13 || $0.value >= 32 }
            .map {
                switch $0 {
                case "&": return "&amp;"
                case "<": return "&lt;"
                case ">": return "&gt;"
                case "\"": return "&quot;"
                case "'": return "&apos;"
                default: return String($0)
                }
            }.joined()
    }
    private static func column(_ number: Int) -> String {
        var n = number, result = ""
        while n > 0 { n -= 1; result = String(UnicodeScalar(65 + n % 26)!) + result; n /= 26 }
        return result
    }
    private static func worksheet(_ rows: [[String]], columnWidths: [Int]) -> String {
        var output = #"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"#
        output += #"<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"/></sheetViews>"#
        output += "<cols>"
        for (index, width) in columnWidths.enumerated() {
            output += #"<col min="\#(index + 1)" max="\#(index + 1)" width="\#(width)" customWidth="1"/>"#
        }
        output += "</cols><sheetData>"
        for (i, row) in rows.enumerated() {
            output += #"<row r="\#(i + 1)">"#
            for (j, value) in row.enumerated() {
                let reference = "\(column(j + 1))\(i + 1)"
                output += #"<c r="\#(reference)" t="inlineStr"><is><t xml:space="preserve">"# +
                    escaped(value) + "</t></is></c>"
            }
            output += "</row>"
        }
        output += "</sheetData></worksheet>"
        return output
    }
    private static func add(_ data: Data, named name: String, archive: Archive) throws {
        try archive.addEntry(with: name, type: .file, uncompressedSize: Int64(data.count),
                             compressionMethod: .deflate, provider: { position, count in
            guard position >= 0, count >= 0, position <= Int64(data.count),
                  Int64(count) <= Int64(data.count) - position else {
                throw DeliveryError.invalid("Invalid Excel ZIP write range")
            }
            return data.subdata(in: Int(position)..<(Int(position) + count))
        })
    }
    static func create(route: DeliveryRoute) throws -> URL {
        let summary = DeliveryReportSummary(route: route)
        let header = ["No", "Postal Code", "Customer", "Phone", "Address",
                      "Amount (SGD)", "Payment Mode", "Status", "ETA (SGT)",
                      "Updated ETA (SGT)", "Delivered At", "Hold Reason", "Hold Note"]
        let customerRows: [[String]] = [header] + route.orders.enumerated().map { index, order in
            [String(index + 1), order.postal, order.name, order.phone, order.address, order.amount,
             order.payment, order.status.rawValue, order.arrival.map(SGFormat.date) ?? "",
             order.updatedArrival.map(SGFormat.date) ?? "",
             order.deliveredAt.map(SGFormat.date) ?? "", order.holdReason ?? "", order.holdNote ?? ""]
        }
        let entries: [(String, Data)] = [
            ("[Content_Types].xml", Data("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>
                """.utf8)),
            ("_rels/.rels", Data("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>
                """.utf8)),
            ("xl/workbook.xml", Data("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets><sheet name="Delivery Summary" sheetId="1" r:id="rId1"/><sheet name="Parcels" sheetId="2" r:id="rId2"/></sheets></workbook>
                """.utf8)),
            ("xl/_rels/workbook.xml.rels", Data("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
                </Relationships>
                """.utf8)),
            ("xl/worksheets/sheet1.xml", Data(worksheet(
                [["Delivery Report Summary", "Value"]] + summary.fields.map { [$0.0, $0.1] },
                columnWidths: [30, 56]).utf8)),
            ("xl/worksheets/sheet2.xml", Data(worksheet(customerRows,
                columnWidths: [8, 15, 27, 18, 54, 16, 18, 14, 23, 23, 23, 23, 32]).utf8))
        ]
        let path = FileManager.default.temporaryDirectory
            .appendingPathComponent("SG-Delivery-Route-\(route.id.uuidString).xlsx")
        try? FileManager.default.removeItem(at: path)
        guard let archive = Archive(url: path, accessMode: .create) else {
            throw DeliveryError.invalid("Could not create Excel file")
        }
        for (name, data) in entries { try add(data, named: name, archive: archive) }
        return path
    }
}
