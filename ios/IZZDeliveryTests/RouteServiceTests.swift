import XCTest
@testable import IZZDelivery

private final class MockMappingProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var response: ((URLRequest) throws -> (Int, String))?
    private static var captured: [URLRequest] = []
    static func configure(_ handler: @escaping (URLRequest) throws -> (Int, String)) { lock.lock(); defer { lock.unlock() }; response = handler; captured = [] }
    static var requests: [URLRequest] { lock.lock(); defer { lock.unlock() }; return captured }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lock.lock(); Self.captured.append(request); let handler = Self.response; Self.lock.unlock()
        do {
            guard let handler, let url = request.url else { throw DeliveryError(message: "No mock mapping response") }
            let (status,body) = try handler(request)
            let http = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type":"application/json"])!
            client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(body.utf8)); client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}
final class RouteServiceTests: XCTestCase {
    private func session() -> URLSession {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [MockMappingProtocol.self]
        return URLSession(configuration: config)
    }
    private func response(_ request: URLRequest, unreachable: Bool = false) throws -> (Int,String) {
        let url = try XCTUnwrap(request.url)
        if url.host == "www.onemap.gov.sg" {
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            XCTAssertEqual(url.path,"/api/common/elastic/search")
            let query = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
            XCTAssertEqual(query.first { $0.name == "returnGeom" }?.value,"Y")
            XCTAssertEqual(query.first { $0.name == "getAddrDetails" }?.value,"Y")
            let code = try XCTUnwrap(query.first { $0.name == "searchVal" }?.value)
            return (200,"{\"results\":[{\"POSTAL\":\"\(code)\",\"LATITUDE\":\"1.3\",\"LONGITUDE\":\"103.8\",\"BLK_NO\":\"1\",\"ROAD_NAME\":\"Road\",\"ADDRESS\":\"1 Road\"}]}")
        }
        if url.path.contains("/table/") {
            if unreachable { return (200,"{\"code\":\"Ok\",\"distances\":[[0,null],[1,0]],\"durations\":[[0,null],[1,0]]}") }
            return (200,"{\"code\":\"Ok\",\"distances\":[[0,1000],[2000,0]],\"durations\":[[0,120],[240,0]]}")
        }
        if url.path.contains("/route/") { return (200,"{\"code\":\"Ok\",\"routes\":[{\"geometry\":{\"coordinates\":[[103.7696,1.4464],[103.8,1.3],[103.7696,1.4464]]}}]}") }
        throw DeliveryError(message: "Unexpected mapping URL")
    }
    func testExactUnauthenticatedPostalSearchAndPersistentRoadCache() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let storage = try ProtectedStorage(directory: directory)
        MockMappingProtocol.configure { try self.response($0) }
        let first = try await RouteService(session: session(), cacheStorage: storage).plan(codes: ["012345"], start: 1_700_000_000, settings: Settings(), location: StartLocation()) { _ in }
        XCTAssertEqual(first.stops[0].place.postal,"012345"); XCTAssertEqual(first.returnLeg.km,2)
        XCTAssertEqual(MockMappingProtocol.requests.count,3)
        // Recreating the service exercises cache persistence, not merely an in-memory repeat.
        let second = try await RouteService(session: session(), cacheStorage: try ProtectedStorage(directory: directory)).plan(codes: ["012345"], start: 1_700_000_060, settings: Settings(), location: StartLocation()) { _ in }
        XCTAssertEqual(second.returnLeg.km,2)
        XCTAssertEqual(MockMappingProtocol.requests.count,4)
        XCTAssertTrue(MockMappingProtocol.requests.last?.url?.path.contains("/route/") == true)
    }
    func testUnreachableRoadStopsPlanningWithoutInventedGeometry() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        MockMappingProtocol.configure { try self.response($0,unreachable: true) }
        do {
            _ = try await RouteService(session: session(), cacheStorage: try ProtectedStorage(directory: directory)).plan(codes: ["012345"], start: 0, settings: Settings(), location: StartLocation()) { _ in }
            XCTFail("Unreachable matrix must stop planning")
        } catch let error as DeliveryError { XCTAssertTrue(error.message.contains("No valid driving route")) }
        XCTAssertEqual(MockMappingProtocol.requests.count,2)
    }
}
