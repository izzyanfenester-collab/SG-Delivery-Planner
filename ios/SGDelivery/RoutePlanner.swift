import Foundation
import CoreLocation

struct SGPlace {
    var postal: String
    var address: String
    var lat: Double
    var lon: Double
}

struct PostalFailure: Identifiable {
    var postal: String
    var reason: String
    var id: String { postal }
}

struct RoutePostalLookupError: LocalizedError {
    let failures: [PostalFailure]
    var errorDescription: String? {
        "Could not check \(failures.count) postal code(s). Review affected customers and retry."
    }
}

enum RoutePlanner {
    static let woodlands = SGPlace(postal: "738203", address: "Woodlands Checkpoint",
                                    lat: 1.4464, lon: 103.7696)
    private struct OneMapResponse: Decodable { let results: [OneMapPlace] }
    private struct OneMapPlace: Decodable {
        let POSTAL: String?
        let LATITUDE: String?
        let LONGITUDE: String?
        let ADDRESS: String?
    }
    private struct RoadMatrix: Decodable {
        let code: String
        let durations: [[Double?]]?
        let distances: [[Double?]]?
    }
    private struct RoadPolyline: Decodable {
        let code: String
        let routes: [PolylineRoute]?
    }
    private struct PolylineRoute: Decodable { let geometry: Geometry? }
    private struct Geometry: Decodable { let coordinates: [[Double]]? }

    static func resolve(_ postal: String) async throws -> SGPlace {
        guard postal.range(of: #"^[0-9]{6}$"#, options: .regularExpression) != nil else {
            throw DeliveryError.invalid("Enter a six-digit Singapore postal code.")
        }
        var components = URLComponents(string: "https://www.onemap.gov.sg/api/common/elastic/search")!
        components.queryItems = [
            URLQueryItem(name: "searchVal", value: postal),
            URLQueryItem(name: "returnGeom", value: "Y"),
            URLQueryItem(name: "getAddrDetails", value: "Y")
        ]
        var request = URLRequest(url: components.url!)
        request.timeoutInterval = 30
        let (data,response) = try await URLSession.shared.data(for: request)
        guard let response = response as? HTTPURLResponse else {
            throw DeliveryError.invalid("No OneMap response. Check internet and retry.")
        }
        guard response.statusCode == 200 else {
            if response.statusCode == 429 { throw DeliveryError.invalid("OneMap is rate limiting. Wait a moment and retry.") }
            throw DeliveryError.invalid("OneMap returned HTTP \(response.statusCode). Retry later.")
        }
        let decoded = try JSONDecoder().decode(OneMapResponse.self, from: data)
        guard let item = decoded.results.first(where: { $0.POSTAL == postal }) else {
            throw DeliveryError.invalid("Postal code \(postal) was not found in OneMap.")
        }
        guard let lat = Double(item.LATITUDE ?? ""),
              let lon = Double(item.LONGITUDE ?? ""),
              (1.1...1.6).contains(lat), (103.5...104.1).contains(lon) else {
            throw DeliveryError.invalid("Postal \(postal) returned invalid Singapore coordinates.")
        }
        return SGPlace(postal: postal, address: item.ADDRESS ?? "Singapore \(postal)", lat: lat, lon: lon)
    }

    static func optimize(_ costs: [[Double]]) throws -> [Int] {
        let n = costs.count
        guard n > 1, costs.allSatisfy({ $0.count == n && $0.allSatisfy({ $0.isFinite && $0 >= 0 }) }) else {
            throw DeliveryError.invalid("Road matrix is incomplete.")
        }
        func cost(_ order: [Int]) -> Double {
            var previous = 0, sum = 0.0
            for i in order { sum += costs[previous][i]; previous = i }
            return sum + costs[previous][0]
        }
        var remaining = Set(1..<n), order: [Int] = [], previous = 0
        while !remaining.isEmpty {
            guard let next = remaining.min(by: { costs[previous][$0] < costs[previous][$1] }) else { break }
            order.append(next)
            remaining.remove(next)
            previous = next
        }
        for _ in 0..<12 {
            var best = cost(order), candidate: [Int]?
            for i in 0..<order.count {
                for j in (i+1)..<order.count {
                    var swapped = order
                    swapped.swapAt(i,j)
                    var reversed = order
                    reversed.replaceSubrange(i...j, with: order[i...j].reversed())
                    for attempt in [swapped,reversed] {
                        let value = cost(attempt)
                        if value < best - 0.01 { best = value; candidate = attempt }
                    }
                }
            }
            guard let candidate else { break }
            order = candidate
        }
        return order
    }

    static func trafficBuffer(km: Double, seconds: Double, mode: String) -> Double {
        guard seconds > 0 else { return 0 }
        let speed = km / (seconds / 3600)
        let fraction = speed < 30 ? 0.24 : (speed < 50 ? 0.16 : 0.10)
        let factor = mode == "Light Traffic" ? 0.6 : (mode == "Heavy Traffic" ? 1.8 : 1.0)
        return (seconds * fraction + min(km,8) * 12) * factor
    }

    static func plan(_ orders: [DeliveryOrder], date: Date, time: Date,
                     minutes: Int = 8, startPostal: String = "738203",
                     trafficMode: String = "Normal",
                     server: String = "https://router.project-osrm.org") async throws -> DeliveryRoute {
        guard (1...50).contains(orders.count), (1...120).contains(minutes) else {
            throw DeliveryError.invalid("Use 1–50 orders and 1–120 minutes per parcel.")
        }
        let root = server.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let rootURL = URL(string: root), rootURL.scheme == "https", rootURL.host != nil else {
            throw DeliveryError.invalid("Routing server must use HTTPS.")
        }
        let unique = Array(Set(orders.map(\.postal))).sorted()
        let startPlace = startPostal == "738203" ? woodlands : try await resolve(startPostal)
        var places = [startPlace]
        var failures: [PostalFailure] = []
        for postal in unique {
            do {
                places.append(try await resolve(postal))
            } catch {
                // Preserve the exact postal and reason. Do not guess an alternative.
                failures.append(PostalFailure(postal: postal, reason: error.localizedDescription))
                if failures.count >= 10 { break }
            }
        }
        if !failures.isEmpty { throw RoutePostalLookupError(failures: failures) }

        let n = places.count
        let coordinates = places.map { "\($0.lon),\($0.lat)" }.joined(separator: ";")
        guard let encoded = coordinates.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed),
              let url = URL(string: "\(root)/table/v1/driving/\(encoded)?annotations=duration,distance") else {
            throw DeliveryError.invalid("Invalid road routing request.")
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 75
        let (data,response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw DeliveryError.invalid("Road routing service unavailable. Your orders are kept.")
        }
        let road = try JSONDecoder().decode(RoadMatrix.self, from: data)
        guard road.code == "Ok", let d = road.durations, let meters = road.distances,
              d.count == n, meters.count == n,
              d.allSatisfy({ $0.count == n && $0.allSatisfy({ $0 != nil && $0!.isFinite && $0! >= 0 }) }),
              meters.allSatisfy({ $0.count == n && $0.allSatisfy({ $0 != nil && $0!.isFinite && $0! >= 0 }) }) else {
            throw DeliveryError.invalid("Incomplete driving distances from the road server. No route saved.")
        }
        let durations = d.map { $0.map { $0! } }, distances = meters.map { $0.map { $0! } }
        let costs = (0..<n).map { i in (0..<n).map { j in
            durations[i][j] +
            trafficBuffer(km: distances[i][j] / 1000, seconds: durations[i][j], mode: trafficMode) +
            distances[i][j] / 1000 * 15
        } }
        let sequence = try optimize(costs)

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = SGFormat.singapore
        let day = calendar.dateComponents([.year,.month,.day], from: date)
        let clock = calendar.dateComponents([.hour,.minute], from: time)
        var components = DateComponents()
        components.year = day.year; components.month = day.month; components.day = day.day
        components.hour = clock.hour; components.minute = clock.minute
        guard var current = calendar.date(from: components) else { throw DeliveryError.invalid("Invalid delivery date") }

        var ordered: [DeliveryOrder] = [], previous = 0, totalMeters = 0.0
        for i in sequence {
            let seconds = durations[previous][i]
            let km = distances[previous][i] / 1000
            current.addTimeInterval(seconds + trafficBuffer(km: km, seconds: seconds, mode: trafficMode))
            let matching = orders.filter { $0.postal == places[i].postal }
            for (index,source) in matching.enumerated() {
                var item = source
                item.latitude = places[i].lat; item.longitude = places[i].lon
                item.legKm = index == 0 ? km : 0
                item.legSeconds = index == 0 ? seconds : 0
                item.arrival = current
                current.addTimeInterval(Double(minutes) * 60)
                item.leave = current
                ordered.append(item)
            }
            totalMeters += distances[previous][i]
            previous = i
        }
        let returnSeconds = durations[previous][0], returnKm = distances[previous][0] / 1000
        current.addTimeInterval(returnSeconds +
            trafficBuffer(km: returnKm, seconds: returnSeconds, mode: trafficMode))
        totalMeters += distances[previous][0]

        let sequenceWithDepot = [0] + sequence + [0]
        let roadPath = sequenceWithDepot.map { "\((places[$0]).lon),\((places[$0]).lat)" }.joined(separator: ";")
        var polyline: [RouteCoordinate]? = nil
        if let pathURL = URL(string: "\(root)/route/v1/driving/\(roadPath)?overview=full&geometries=geojson&steps=false") {
            var lineRequest = URLRequest(url: pathURL)
            lineRequest.timeoutInterval = 60
            if let fetched = try? await URLSession.shared.data(for: lineRequest),
               (fetched.1 as? HTTPURLResponse)?.statusCode == 200,
               let geometry = try? JSONDecoder().decode(RoadPolyline.self, from: fetched.0),
               geometry.code == "Ok", let points = geometry.routes?.first?.geometry?.coordinates {
                polyline = points.compactMap {
                    guard $0.count >= 2 else { return nil }
                    return RouteCoordinate(latitude: $0[1], longitude: $0[0])
                }
            }
        }
        return DeliveryRoute(date: date, startTime: time,
            startName: startPostal == "738203" ? "Woodlands Checkpoint" : "Custom Depot",
            startPostal: startPostal, depotLatitude: places[0].lat, depotLongitude: places[0].lon,
            minutesPerStop: minutes, orders: ordered, totalKm: totalMeters / 1000,
            returnETA: current, optimized: true, roadGeometry: polyline, trafficMode: trafficMode)
    }
}
