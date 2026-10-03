import Foundation

/// Small dependency-free OOXML writer. ZIP uses stored entries with standard CRC32.
/// Postal/address cells are inline strings, never formulas; timestamps include the Singapore date.
enum ExcelWorkbook {
    static func filename(_ route: Route) -> String { "IZZ_Delivery_\(SingaporeTime.day(route.start)).xlsx" }
    static func make(_ route: Route) throws -> Data {
        let s = try SharedBridge.summary(route)
        var rows: [String] = []
        func row(_ cells: [(String, Bool, Int)]) {
            let number = rows.count+1
            let content = cells.enumerated().map { i, cell -> String in
                let ref = "\(column(i))\(number)"
                if cell.1 { return "<c r=\"\(ref)\" s=\"\(cell.2)\"><v>\(cell.0)</v></c>" }
                return "<c r=\"\(ref)\" t=\"inlineStr\" s=\"\(cell.2)\"><is><t xml:space=\"preserve\">\(xml(cell.0))</t></is></c>"
            }.joined()
            rows.append("<row r=\"\(number)\" ht=\"32\" customHeight=\"1\">\(content)</row>")
        }
        func text(_ value: String, _ style: Int = 0) -> (String,Bool,Int) { (value,false,style) }
        func number(_ value: Double, _ style: Int = 2) -> (String,Bool,Int) { (String(value),true,style) }
        func date(_ epoch: Int64) -> (String,Bool,Int) { number(Double(epoch)/86400+25569+8.0/24,3) }
        row([text("IZZ Delivery - Complete Delivery Schedule",1)]); row([])
        row([text("IZZ Delivery Route Summary",1)])
        row([text("Date"),text(SingaporeTime.day(route.start))])
        row([text("Start Location"),text(route.startLocation.label+" — "+route.startLocation.address)])
        row([text("End Location"),text(route.startLocation.label+" — "+route.startLocation.address)])
        row([text("Start Time"),date(s.start)]); row([text("Finish Time"),date(s.finish)])
        row([text("Total Parcel"),number(Double(s.totalParcel),6)])
        row([text("Delivered"),number(Double(s.delivered),6)])
        row([text("On Hold"),number(Double(s.onHold),6)])
        row([text("Skipped"),number(Double(s.skipped),6)])
        row([text("Pending"),number(Double(s.pending),6)])
        row([text("Success Rate"),number(s.successRate/100,5)])
        row([text("Total KM"),number(s.totalKm)])
        row([text("Cash on Hand"),number(Double(s.cashOnHand.amount) ?? 0,4)])
        row([text("Tax"),number(Double(s.tax.amount) ?? 0,4)])
        row([text("Planned Return"),date(s.returned)]); row([])
        let headerRow = rows.count+1
        let headers = ["Stop", "Postal Code", "Block", "Area", "Arrival", "Distance From Previous", "Base Drive", "Traffic Buffer", "Planned Travel", "Delivery", "Leave", "Cumulative KM"]
        row(headers.map { text($0,1) })
        row([text("START"),text(route.startLocation.postal),text(route.startLocation.label),text(route.startLocation.address),date(route.start),number(0),number(0,7),number(0,7),number(0,7),number(0,7),date(route.start),number(0)])
        for (index,stop) in route.stops.enumerated() {
            row([number(Double(index+1),6),text(stop.place.postal),text(stop.place.block),text(stop.place.area),date(stop.arrival),number(stop.leg.km),number(stop.leg.baseSeconds/60,7),number(stop.leg.bufferSeconds/60,7),number(stop.leg.plannedSeconds/60,7),number(Double(route.serviceMinutes),7),date(stop.leave),number(stop.cumulativeKm)])
        }
        row([text("END"),text(route.startLocation.postal),text(route.startLocation.label),text(route.startLocation.address),date(route.returned),number(route.returnLeg.km),number(route.returnLeg.baseSeconds/60,7),number(route.returnLeg.bufferSeconds/60,7),number(route.returnLeg.plannedSeconds/60,7),number(0,7),date(route.returned),number(s.totalKm)])
        let widths = [16,16,28,42,24,22,18,18,20,16,24,20]
        let cols = widths.enumerated().map { "<col min=\"\($0.offset+1)\" max=\"\($0.offset+1)\" width=\"\($0.element)\" customWidth=\"1\"/>" }.joined()
        let worksheet = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="\(headerRow)" topLeftCell="A\(headerRow+1)" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>\(cols)</cols><sheetData>\(rows.joined())</sheetData><autoFilter ref="A\(headerRow):L\(rows.count)"/><mergeCells count="2"><mergeCell ref="A1:L1"/><mergeCell ref="A3:L3"/></mergeCells></worksheet>
        """
        let files: [(String,String)] = [
            ("[Content_Types].xml", """
            <?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>
            """),
            ("_rels/.rels", """
            <?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>
            """),
            ("xl/workbook.xml", """
            <?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Delivery Schedule" sheetId="1" r:id="rId1"/></sheets></workbook>
            """),
            ("xl/_rels/workbook.xml.rels", """
            <?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>
            """),
            ("xl/styles.xml", styles), ("xl/worksheets/sheet1.xml",worksheet)
        ]
        return StoredZIP.make(files.map { ($0.0, Data($0.1.utf8)) })
    }
    private static func column(_ index: Int) -> String { String(UnicodeScalar(65+index)!) }
    private static func xml(_ value: String) -> String {
        let filtered = String(String.UnicodeScalarView(value.unicodeScalars.filter { $0.value == 9 || $0.value == 10 || $0.value == 13 || ($0.value >= 32 && $0.value != 0xFFFE && $0.value != 0xFFFF) }))
        return filtered.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;").replacingOccurrences(of: "'", with: "&apos;")
    }
    private static let styles = """
    <?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
    <numFmts count="6"><numFmt numFmtId="164" formatCode="0.00 &quot;KM&quot;"/><numFmt numFmtId="165" formatCode="yyyy-mm-dd hh:mm"/><numFmt numFmtId="166" formatCode="&quot;SGD &quot;#,##0.00"/><numFmt numFmtId="167" formatCode="0.0%"/><numFmt numFmtId="168" formatCode="0"/><numFmt numFmtId="169" formatCode="0.0 &quot;min&quot;"/></numFmts>
    <fonts count="2"><font><sz val="11"/><name val="Calibri"/><color rgb="FF10213A"/></font><font><b/><sz val="12"/><name val="Calibri"/><color rgb="FFDEB35E"/></font></fonts>
    <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF081326"/><bgColor indexed="64"/></patternFill></fill></fills>
    <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
    <cellXfs count="8"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment wrapText="1" vertical="center"/></xf><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="166" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="167" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="168" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="169" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>
    """
}
enum StoredZIP {
    static func make(_ files: [(String,Data)]) -> Data {
        var data = Data(); var central = Data()
        for (name,content) in files {
            let filename = Data(name.utf8); let crc = crc32(content); let offset = UInt32(data.count); let size = UInt32(content.count)
            data.le(UInt32(0x04034b50)); data.le(UInt16(20)); data.le(UInt16(0x0800)); data.le(UInt16(0)); data.le(UInt16(0)); data.le(UInt16(33))
            data.le(crc); data.le(size); data.le(size); data.le(UInt16(filename.count)); data.le(UInt16(0)); data.append(filename); data.append(content)
            central.le(UInt32(0x02014b50)); central.le(UInt16(20)); central.le(UInt16(20)); central.le(UInt16(0x0800)); central.le(UInt16(0)); central.le(UInt16(0)); central.le(UInt16(33))
            central.le(crc); central.le(size); central.le(size); central.le(UInt16(filename.count)); central.le(UInt16(0)); central.le(UInt16(0)); central.le(UInt16(0)); central.le(UInt16(0)); central.le(UInt32(0)); central.le(offset); central.append(filename)
        }
        let start = UInt32(data.count); data.append(central)
        data.le(UInt32(0x06054b50)); data.le(UInt16(0)); data.le(UInt16(0)); data.le(UInt16(files.count)); data.le(UInt16(files.count)); data.le(UInt32(central.count)); data.le(start); data.le(UInt16(0))
        return data
    }
    static func crc32(_ data: Data) -> UInt32 {
        var crc = UInt32.max
        for byte in data { crc ^= UInt32(byte); for _ in 0..<8 { crc = (crc >> 1) ^ ((crc & 1) == 1 ? 0xedb88320 : 0) } }
        return crc ^ UInt32.max
    }
}
private extension Data {
    mutating func le<T: FixedWidthInteger>(_ number: T) { var value = number.littleEndian; Swift.withUnsafeBytes(of: &value) { append(contentsOf: $0) } }
}
