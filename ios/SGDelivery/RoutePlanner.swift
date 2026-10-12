import Foundation
import CoreLocation

struct SGPlace {
    var postal: String
    var address: String
    var lat: Double
    var lon: Double
}

enum RoutePlanner {
    static let woodlands = SGPlace(postal: "738203", address: "Woodlands Checkpoint", lat: 1.4464, lon: 103.7696)
    private struct OneMapResponse: Decodable {
        let results: [OneMapPlace]
    }
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

    static func resolve(_ postal: String) async throws -> SGPlace {
        guard postal.range(of: #"^[0-9]{6}$"#, options: .regularExpression) != nil else {
            throw DeliveryError.invalid("Enter a Singapore 6-digit postal code")
        }
        var components = URLComponents(string: "https://www.onemap.gov.sg/api/common/elastic/search")!
        components.queryItems = [
            URLQueryItem(name: "searchVal", value: postal),
            URLQueryItem(name: "returnGeom", value: "Y"),
            URLQueryItem(name: "getAddrDetails", value: "Y")
        ]
        var request = URLRequest(url: components.url!)
        request.timeoutInterval = 18
        let (data,response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw DeliveryError.invalid("OneMap unavailable. Check connection and try again.")
        }
        let decoded = try JSONDecoder().decode(OneMapResponse.self, from: data)
        guard let item = decoded.results.first(where: { $0.POSTAL == postal }),
              let lat = Double(item.LATITUDE ?? ""), let lon = Double(item.LONGITUDE ?? ""),
              (1.1...1.6).contains(lat), (103.5...104.1).contains(lon) else {
            throw DeliveryError.invalid("OneMap could not resolve postal \(postal)")
        }
        return SGPlace(postal: postal, address: item.ADDRESS ?? "Singapore \(postal)", lat: lat, lon: lon)
    }

    static func optimize(_ costs: [[Double]]) throws -> [Int] {
        let n = costs.count
        guard n > 1, costs.allSatisfy({ $0.count == n && $0.allSatisfy({ $0.isFinite && $0 >= 0 }) }) else {
            throw DeliveryError.invalid("Road matrix is invalid")
        }
        func cost(_ order: [Int]) -> Double {
            var previous = 0, sum = 0.0
            for i in order { sum += costs[previous][i]; previous = i }
            return sum + costs[previous][0]
        }
        var remaining = Set(1..<n), order: [Int] = [], previous = 0
        while !remaining.isEmpty {
            let next = remaining.min { costs[previous][$0] < costs[previous][$1] }!
            order.append(next); remaining.remove(next); previous = next
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

    static func trafficBuffer(km: Double, seconds: Double) -> Double {
        guard seconds > 0 else { return 0 }
        let speed = km / (seconds / 3600)
        let fraction = speed < 30 ? 0.24 : (speed < 50 ? 0.16 : 0.10)
        return seconds * fraction + min(km,8) * 12
    }

    static func plan(_ orders: [DeliveryOrder], date: Date, time: Date,
                     minutes: Int = 8, startPostal: String = "738203") async throws -> DeliveryRoute {
        guard (1...50).contains(orders.count), (1...120).contains(minutes) else {
            throw DeliveryError.invalid("Use 1–50 orders and 1–120 minutes per stop")
        }
        let unique = Array(Set(orders.map(\.postal))).sorted()
        let depot = startPostal == "738203" ? woodlands : try await resolve(startPostal)
        var places = [depot]
        for postal in unique { places.append(try await resolve(postal)) }

        let coordinates = places.map { "\($0.lon),\($0.lat)" }.joined(separator: ";")
        guard let encoded = coordinates.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed),
              let url = URL(string: "https://router.project-osrm.org/table/v1/driving/\(encoded)?annotations=duration,distance") else {
            throw DeliveryError.invalid("Invalid routing request")
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 60
        let (data,response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw DeliveryError.invalid("Road routing unavailable. No estimated route was saved.")
        }
        let road = try JSONDecoder().decode(RoadMatrix.self, from: data)
        let n = places.count
        guard road.code == "Ok", let d = road.durations, let meters = road.distances,
              d.count == n, meters.count == n,
              d.allSatisfy({ $0.count == n && $0.allSatisfy({ $0 != nil && $0!.isFinite && $0! >= 0 }) }),
              meters.allSatisfy({ $0.count == n && $0.allSatisfy({ $0 != nil && $0!.isFinite && $0! >= 0 }) }) else {
            throw DeliveryError.invalid("Road service returned incomplete distances; route not saved.")
        }
        let durations = d.map { $0.map { $0! } }, distances = meters.map { $0.map { $0! } }
        let sequence = try optimize(durations)

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
            let legSeconds = durations[previous][i]
            let legKm = distances[previous][i] / 1000
            current.addTimeInterval(legSeconds + trafficBuffer(km: legKm, seconds: legSeconds))
            let matching = orders.filter { $0.postal == places[i].postal }
            for (index,source) in matching.enumerated() {
                var item = source
                item.latitude = places[i].lat; item.longitude = places[i].lon
                item.legKm = index == 0 ? legKm : 0
                item.legSeconds = index == 0 ? legSeconds : 0
                item.arrival = current
                current.addTimeInterval(Double(minutes) * 60)
                item.leave = current
                ordered.append(item)
            }
            totalMeters += distances[previous][i]
            previous = i
        }
        let returnSeconds = durations[previous][0], returnKm = distances[previous][0] / 1000
        current.addTimeInterval(returnSeconds + trafficBuffer(km: returnKm, seconds: returnSeconds))
        totalMeters += distances[previous][0]
        return DeliveryRoute(date: date, startTime: time,
            startName: startPostal == "738203" ? "Woodlands Checkpoint" : "Custom Depot",
            startPostal: startPostal, depotLatitude: depot.lat, depotLongitude: depot.lon, minutesPerStop: minutes,
            orders: ordered, totalKm: totalMeters / 1000, returnETA: current, optimized: true)
    }
}
