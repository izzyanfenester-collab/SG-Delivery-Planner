import XCTest
@testable import IZZDelivery

final class DeliveryTests: XCTestCase {
    private func fixture(count: Int = 4) throws -> Route {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "Asia/Singapore")!
        let start = calendar.date(from: DateComponents(year: 2026, month: 10, day: 4, hour: 23, minute: 59))!.epoch
        var places: [Place] = []
        for index in 0..<count {
            let postal = index == 0 ? "012345" : String(100000 + index)
            let latitude = 1.3 + Double(index) * 0.001
            let place = Place(postal: postal, lat: latitude, lon: 103.8, block: "=1+1", area: "道路 & Road", address: "1 Road")
            places.append(place)
        }
        let tour = Tour(order: Array(1...count), places: places, legs: Array(repeating: Leg(km: 1.25, baseSeconds: 120, bufferSeconds: 50), count: count+1))
        let home = StartLocation(type: "HOME", lat: 1.31, lon: 103.81, label: "My Home", address: "Home & Road", postal: "001234")
        return try SharedBridge.schedule(ScheduleInput(id: "fixture", created: start, start: start, serviceMinutes: 8, mode: "Normal Traffic", tour: tour, geometry: [[103.81,1.31],[103.8,1.3]], startLocation: home))
    }
    func testNativeBridgePreservesPostalValidationAndDirectedOptimization() throws {
        let parsed = try SharedBridge.parse("012345, 123456; 012345 bad")
        XCTAssertEqual(parsed.valid,["012345","123456"]); XCTAssertEqual(parsed.invalid,["bad"]); XCTAssertEqual(parsed.duplicates,1)
        let p = Place(postal: "012345", lat: 1.3, lon: 103.8, block: "1", area: "Road", address: "1 Road")
        let tour = try SharedBridge.optimize(RoadMatrix(places: [p,p], meters: [[0,1000],[2000,0]], seconds: [[0,120],[240,0]], mode: "Normal Traffic"))
        XCTAssertEqual(tour.order,[1]); XCTAssertEqual(tour.legs.count,2); XCTAssertEqual(tour.legs[1].km,2)
    }
    func testPendingReviewIsSeparateFromDeliveryAndMoneyIsIndependent() throws {
        var r = try fixture()
        let start = r.start
        for (i,action) in ["DELIVERED","ON_HOLD","SKIPPED","NEXT"].enumerated() {
            r = try SharedBridge.review(ReviewInput(route: r, action: action, now: start+Int64(i+1)*60, holdReason: action == "ON_HOLD" ? "Other" : nil, holdNote: "Customer requested tomorrow"))
        }
        let s = try SharedBridge.summary(r)
        XCTAssertEqual([s.delivered,s.onHold,s.skipped,s.pending],[1,1,1,1]); XCTAssertEqual(s.successRate,25)
        XCTAssertNil(r.actualCompletion); XCTAssertEqual(r.current,4); XCTAssertNotNil(r.reviewedFinishedAt)
        r = try SharedBridge.saveMoney(MoneyInput(route: r, cashOnHand: "100.1", tax: "5", now: start+300))
        XCTAssertEqual(r.cashOnHand.amount,"100.10"); XCTAssertEqual(r.tax.amount,"5.00")
        XCTAssertThrowsError(try SharedBridge.saveMoney(MoneyInput(route: r, cashOnHand: "-1", tax: "5.123", now: start)))
        r.current = 1
        r = try SharedBridge.review(ReviewInput(route: r, action: "DELIVERED", now: start+400, holdReason: nil, holdNote: nil))
        XCTAssertNil(r.stops[1].holdReason); XCTAssertNil(r.stops[1].holdNote); XCTAssertNil(r.summarySavedAt)
    }
    @MainActor func testProtectedPersistenceRestoresProgressHomeAndSavedMoney() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let storage = try ProtectedStorage(directory: directory)
        var route = try fixture()
        route = try SharedBridge.review(ReviewInput(route: route, action: "ON_HOLD", now: route.start+60, holdReason: "Other", holdNote: "Tomorrow"))
        route = try SharedBridge.saveMoney(MoneyInput(route: route, cashOnHand: "100", tax: "9.50", now: route.start+120))
        let store = AppStore(storage: storage)
        XCTAssertTrue(store.save(route)); XCTAssertTrue(store.update { $0.home = route.startLocation; $0.postalText = "012345" })
        let restored = AppStore(storage: try ProtectedStorage(directory: directory))
        XCTAssertEqual(restored.route?.current,1); XCTAssertEqual(restored.route?.stops[0].holdReason,"Other")
        XCTAssertEqual(restored.route?.stops[0].holdNote,"Tomorrow"); XCTAssertEqual(restored.route?.cashOnHand.amount,"100.00")
        XCTAssertEqual(restored.route?.tax.amount,"9.50"); XCTAssertEqual(restored.state.home?.postal,"001234")
        XCTAssertEqual(restored.state.postalText,"012345"); XCTAssertEqual(restored.state.routes.count,1)
        let values = try directory.resourceValues(forKeys: [.isExcludedFromBackupKey]); XCTAssertEqual(values.isExcludedFromBackup,true)
        let attrs = try FileManager.default.attributesOfItem(atPath: directory.appendingPathComponent("state.json").path)
        XCTAssertEqual(attrs[.protectionKey] as? FileProtectionType,.complete)
    }
    func testExcel50StopsMidnightAndCustomHomeForIndependentReader() throws {
        var route = try fixture(count: 50)
        let start = route.start
        for i in 0..<50 {
            let action = i == 0 ? "DELIVERED" : i == 1 ? "ON_HOLD" : i == 2 ? "SKIPPED" : "NEXT"
            route = try SharedBridge.review(ReviewInput(route: route, action: action, now: start+Int64(i+1)*60, holdReason: i == 1 ? "Other" : nil, holdNote: "Tomorrow"))
        }
        route = try SharedBridge.saveMoney(MoneyInput(route: route, cashOnHand: "99.10", tax: "5.20", now: start+3600))
        let data = try ExcelWorkbook.make(route)
        XCTAssertEqual(Array(data.prefix(4)),[0x50,0x4b,0x03,0x04])
        XCTAssertEqual(StoredZIP.crc32(Data("123456789".utf8)),0xcbf43926)
        XCTAssertEqual(ExcelWorkbook.filename(route),"IZZ_Delivery_2026-10-04.xlsx")
        // macOS CI reads this simulator-container fixture with openpyxl after XCTest completes.
        try data.write(to: FileManager.default.temporaryDirectory.appendingPathComponent("IZZ-ExcelRegression.xlsx"), options: .atomic)
        XCTAssertEqual(route.startLocation.label,"My Home")
        XCTAssertNotEqual(SingaporeTime.day(route.stops[0].arrival),SingaporeTime.day(route.start))
    }
    func testInvalidMatrixFailsWithoutStraightLineFallback() throws {
        let p = Place(postal: "012345", lat: 1.3, lon: 103.8, block: "1", area: "", address: "")
        XCTAssertThrowsError(try SharedBridge.optimize(RoadMatrix(places: [p,p], meters: [[0,1]], seconds: [[0,1],[1,0]], mode: "Normal Traffic")))
        XCTAssertFalse(RouteService.validEndpoint("http://router.example.com")); XCTAssertFalse(RouteService.validEndpoint("https://user:secret@router.example.com"))
        XCTAssertTrue(RouteService.validEndpoint("https://router.project-osrm.org"))
    }
}
