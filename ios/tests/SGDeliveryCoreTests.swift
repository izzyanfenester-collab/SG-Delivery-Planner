import XCTest
import UIKit
import MapKit
import ZIPFoundation
@testable import SGDeliveryV3

/// Integration tests executed in an actual iOS Simulator (not just macOS swiftc).
final class SGDeliveryCoreTests: XCTestCase {
    private func sampleRoute() -> DeliveryRoute {
        let now = Date(timeIntervalSince1970: 1_760_000_000)
        let orderID = UUID()
        let photo = UIGraphicsImageRenderer(size: CGSize(width: 100, height: 100))
            .jpegData(withCompressionQuality: 0.7) { context in
                UIColor.blue.setFill()
                context.fill(CGRect(x: 0, y: 0, width: 100, height: 100))
            }
        let first = DeliveryOrder(
            id: orderID, postal: "730171", name: "Test Customer A",
            phone: "+6590123456", address: "Blk 171 Woodlands Singapore 730171",
            amount: "65.00", payment: "COD", originalMessage: "Order A\nItem: Blue",
            status: .delivered, reviewedAt: now, deliveredAt: now,
            arrival: now, updatedArrival: now, leave: now.addingTimeInterval(480),
            latitude: 1.44, longitude: 103.78,
            proofDelivery: photo.base64EncodedString())
        let second = DeliveryOrder(
            postal: "730171", name: "Test Customer B",
            phone: "+6590234567", address: "Blk 171 Woodlands Singapore 730171",
            amount: "75.00", payment: "PayNow", originalMessage: "Order B\nItem: Red",
            status: .hold, reviewedAt: now, holdReason: "No answer",
            arrival: now.addingTimeInterval(480),
            leave: now.addingTimeInterval(960),
            latitude: 1.44, longitude: 103.78)
        return DeliveryRoute(date: now, startTime: now,
            orders: [first, second], totalKm: 12.3,
            returnETA: now.addingTimeInterval(5500), optimized: true,
            cashOnHand: "65.00", tax: "15.00", exchangeRate: "3.60",
            remark: "Integration test")
    }

    func testAndroidCompatibleZipRoundTrip() throws {
        let original = sampleRoute()
        let zip = try AndroidBackupZIP.exportArchive([original])
        defer { try? FileManager.default.removeItem(at: zip) }
        let archive = try XCTUnwrap(Archive(url: zip, accessMode: .read))
        XCTAssertNotNil(archive["manifest.json"])
        XCTAssertNotNil(archive["routes.json"])
        XCTAssertEqual(archive.filter { $0.path.hasPrefix("proofs/") }.count, 1)

        let imported = try AndroidBackupZIP.importArchive(zip)
        XCTAssertEqual(imported.routes.count, 1)
        XCTAssertEqual(imported.missingProofs, 0)
        let output = try XCTUnwrap(imported.routes.first)
        XCTAssertEqual(output.id, original.id)
        XCTAssertEqual(output.orders.count, 2)
        XCTAssertEqual(output.orders.map(\.id), original.orders.map(\.id))
        XCTAssertEqual(output.orders.map(\.name), original.orders.map(\.name))
        XCTAssertEqual(output.orders.map(\.originalMessage), original.orders.map(\.originalMessage))
        XCTAssertEqual(output.orders.first?.status, .delivered)
        XCTAssertEqual(output.orders.last?.status, .hold)
        XCTAssertNotNil(output.orders.first?.proofDelivery)
        XCTAssertEqual(output.cashOnHand, "65.00")
        XCTAssertEqual(output.tax, "15.00")
        XCTAssertEqual(output.exchangeRate, "3.60")
    }

    func testActualXLSXArchiveContainsValidWorksheets() throws {
        let file = try DeliveryExcel.create(route: sampleRoute())
        defer { try? FileManager.default.removeItem(at: file) }
        let archive = try XCTUnwrap(Archive(url: file, accessMode: .read))
        for name in ["[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                     "xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml",
                     "xl/worksheets/sheet2.xml"] {
            XCTAssertNotNil(archive[name], "\(name) must exist")
        }
        func read(_ name: String) throws -> String {
            let entry = try XCTUnwrap(archive[name])
            var payload = Data()
            let _ = try archive.extract(entry, consumer: { payload.append($0) })
            return try XCTUnwrap(String(data: payload, encoding: .utf8))
        }
        let workbook = try read("xl/workbook.xml")
        XCTAssertTrue(workbook.contains("Delivery Summary"))
        XCTAssertTrue(workbook.contains("Parcels"))
        let deliveries = try read("xl/worksheets/sheet2.xml")
        XCTAssertTrue(deliveries.contains("Test Customer A"))
        XCTAssertTrue(deliveries.contains("Test Customer B"))
        XCTAssertTrue(deliveries.contains("Order") == false,
                      "Original raw order should not appear in report unless separately requested")
    }

    func testCameraJPEGAndRoadETAEstimation() throws {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 150, height: 150))
            .image { context in UIColor.green.setFill()
                context.fill(CGRect(x: 0, y: 0, width: 150, height: 150))
            }
        let data = try XCTUnwrap(ProofImage.encode(image))
        XCTAssertNotNil(UIImage(data: data))
        XCTAssertLessThan(data.count, 1_000_000)

        let path = [
            CLLocationCoordinate2D(latitude: 1.44, longitude: 103.77),
            CLLocationCoordinate2D(latitude: 1.44, longitude: 103.78),
            CLLocationCoordinate2D(latitude: 1.45, longitude: 103.78)
        ]
        let meters = try XCTUnwrap(NavigationMath.remainingRoadMeters(current: path[1], path: path))
        XCTAssertGreaterThan(meters, 800)
        XCTAssertLessThan(meters, 1500)
        XCTAssertGreaterThan(NavigationMath.remainingSeconds(roadMeters: meters, routeMeters: 2200,
                                                             routedSeconds: 800), 200)
    }
}
