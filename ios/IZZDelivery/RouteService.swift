import Foundation

actor RouteService {
    struct CachedPlace: Codable { let place: Place; let saved: Date }
    struct CachedRoad: Codable { let km: Double; let seconds: Double; let saved: Date }
    struct Cache: Codable { var places: [String: CachedPlace] = [:]; var roads: [String: CachedRoad] = [:] }
    private var cache = Cache()
    private var storage: ProtectedStorage?
    private let session: URLSession
    private let cacheStorage: ProtectedStorage?
    init(session: URLSession? = nil, cacheStorage: ProtectedStorage? = nil) {
        let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 45; config.timeoutIntervalForResource = 120
        self.session = session ?? URLSession(configuration: config)
        self.cacheStorage = cacheStorage
    }
    static func validEndpoint(_ raw: String) -> Bool {
        guard let c = URLComponents(string: raw), c.scheme == "https", let host = c.host, !host.isEmpty,
              c.user == nil, c.password == nil, c.query == nil, c.fragment == nil else { return false }
        return true
    }
    private func prepareCache() throws {
        if storage == nil {
            let store = try cacheStorage ?? ProtectedStorage(); cache = try store.load("network-cache.json", as: Cache.self) ?? Cache(); storage = store
        }
    }
    private func saveCache() throws { try storage?.save(cache, name: "network-cache.json") }
    private func get(_ url: URL) async throws -> (Data, HTTPURLResponse) {
        try Task.checkCancellation()
        var request = URLRequest(url: url); request.setValue("com.izzyan.sgdeliveryplanner.ios/1.0", forHTTPHeaderField: "User-Agent")
        let (data, response): (Data, URLResponse)
        do { (data, response) = try await session.data(for: request) }
        catch is CancellationError { throw CancellationError() }
        catch { if Task.isCancelled { throw CancellationError() }; throw DeliveryError(message: "Could not reach the mapping service. Check your connection and try again. Your entries have been kept.") }
        guard let http = response as? HTTPURLResponse else { throw DeliveryError(message: "The mapping service returned an invalid response.") }
        return (data, http)
    }
    private struct Search: Decodable {
        struct Result: Decodable { let POSTAL: String; let LATITUDE: String; let LONGITUDE: String; let BLK_NO: String; let ROAD_NAME: String; let ADDRESS: String }
        let results: [Result]
    }
    private func resolve(_ postal: String) async throws -> Place {
        if let saved = cache.places[postal], Date().timeIntervalSince(saved.saved) < 180*86400 { return saved.place }
        var components = URLComponents(string: "https://www.onemap.gov.sg/api/common/elastic/search")!
        components.queryItems = [URLQueryItem(name: "searchVal", value: postal), .init(name: "returnGeom", value: "Y"), .init(name: "getAddrDetails", value: "Y")]
        for attempt in 0...3 {
            let (data,http) = try await get(components.url!)
            if http.statusCode == 429 {
                guard attempt < 3 else { throw DeliveryError(message: "Postal lookup is busy. Please wait and try again; your entries have been kept.") }
                let delay = retryDelay(http.value(forHTTPHeaderField: "Retry-After"), fallback: pow(2,Double(attempt)))
                guard delay <= 30 else { throw DeliveryError(message: "Postal lookup needs a longer cooldown. Please try again later.") }
                try await Task.sleep(nanoseconds: UInt64(delay*1_000_000_000)); continue
            }
            guard (200...299).contains(http.statusCode) else { throw DeliveryError(message: "Postal lookup is unavailable (HTTP \(http.statusCode)). Please try again later.") }
            let search: Search
            do { search = try JSONDecoder().decode(Search.self, from: data) }
            catch { throw DeliveryError(message: "Postal lookup returned invalid address data. Please try again.") }
            guard let result = search.results.first(where: { $0.POSTAL == postal }),
                  let lat = Double(result.LATITUDE), let lon = Double(result.LONGITUDE), lat.isFinite, lon.isFinite,
                  (1.15...1.5).contains(lat), (103.6...104.1).contains(lon) else {
                throw DeliveryError(message: "No exact Singapore address was found for \(postal). Check the postal code.")
            }
            let place = Place(postal: postal, lat: lat, lon: lon, block: result.BLK_NO, area: result.ROAD_NAME, address: result.ADDRESS)
            cache.places[postal] = CachedPlace(place: place, saved: Date()); try saveCache(); return place
        }
        throw DeliveryError(message: "Postal lookup is unavailable.")
    }
    private func retryDelay(_ raw: String?, fallback: Double) -> Double {
        guard let raw else { return fallback }
        if let seconds = Double(raw), seconds.isFinite { return max(fallback, seconds) }
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.timeZone = TimeZone(secondsFromGMT: 0); formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss z"
        return max(fallback, formatter.date(from: raw)?.timeIntervalSinceNow ?? fallback)
    }
    private struct Table: Decodable { let code: String; let distances: [[Double?]]?; let durations: [[Double?]]? }
    private struct RoadRoute: Decodable {
        struct Result: Decodable { struct Geometry: Decodable { let coordinates: [[Double]] }; let geometry: Geometry }
        let code: String; let routes: [Result]?
    }
    func plan(codes: [String], start: Int64, settings: Settings, location: StartLocation,
              progress: @Sendable (String) async -> Void) async throws -> Route {
        guard codes.count >= 1 && codes.count <= 50, location.valid, Self.validEndpoint(settings.endpoint) else {
            throw DeliveryError(message: "Use 1–50 postal codes, a valid Start & End and an HTTPS routing server without credentials.")
        }
        try prepareCache()
        var places = [location.place]
        for (index, code) in codes.enumerated() {
            await progress("Checking postal code \(index+1) of \(codes.count)…")
            places.append(try await resolve(code))
        }
        let root = settings.endpoint.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        func key(_ a: Place, _ b: Place) -> String { "\(root)|\(a.lat),\(a.lon)|\(b.lat),\(b.lon)" }
        let n = places.count
        var meters = Array(repeating: Array(repeating: 0.0, count: n), count: n); var seconds = meters; var complete = true
        for i in 0..<n { for j in 0..<n where i != j {
            if let road = cache.roads[key(places[i],places[j])], Date().timeIntervalSince(road.saved) < 7*86400 {
                meters[i][j] = road.km*1000; seconds[i][j] = road.seconds
            } else { complete = false }
        } }
        if !complete {
            await progress("Finding road distances and driving times…")
            let coordinates = places.map { "\($0.lon),\($0.lat)" }.joined(separator: ";")
            guard let url = URL(string: root+"/table/v1/driving/"+coordinates+"?annotations=distance,duration") else { throw DeliveryError(message: "Invalid routing server address.") }
            let (data,http) = try await get(url)
            guard (200...299).contains(http.statusCode) else { throw DeliveryError(message: "The routing server is unavailable (HTTP \(http.statusCode)).") }
            let table = try JSONDecoder().decode(Table.self, from: data)
            guard table.code == "Ok", let distances = table.distances, let durations = table.durations,
                  distances.count == n, durations.count == n, distances.allSatisfy({ $0.count == n }), durations.allSatisfy({ $0.count == n }) else {
                throw DeliveryError(message: "The routing server could not calculate a complete road matrix. Check Settings.")
            }
            for i in 0..<n { for j in 0..<n {
                guard let distance = distances[i][j], let duration = durations[i][j], distance.isFinite, duration.isFinite, distance >= 0, duration >= 0 else {
                    throw DeliveryError(message: "No valid driving route was found between \(places[i].address) and \(places[j].address).")
                }
                meters[i][j] = distance; seconds[i][j] = duration
                if i != j { cache.roads[key(places[i],places[j])] = CachedRoad(km: distance/1000, seconds: duration, saved: Date()) }
            } }
            try saveCache()
        }
        await progress("Improving the delivery order…")
        let tour = try SharedBridge.optimize(RoadMatrix(places: places, meters: meters, seconds: seconds, mode: settings.mode))
        let coordinates = ([location.place]+tour.places+[location.place]).map { "\($0.lon),\($0.lat)" }.joined(separator: ";")
        await progress("Loading the route map…")
        guard let url = URL(string: root+"/route/v1/driving/"+coordinates+"?overview=full&geometries=geojson&steps=false") else { throw DeliveryError(message: "Invalid routing server address.") }
        let (data,http) = try await get(url)
        guard (200...299).contains(http.statusCode) else { throw DeliveryError(message: "The route map is unavailable. Please try again.") }
        let response = try JSONDecoder().decode(RoadRoute.self, from: data)
        guard response.code == "Ok", let geometry = response.routes?.first?.geometry.coordinates, geometry.count >= 2,
              geometry.allSatisfy({ $0.count >= 2 && $0[0].isFinite && $0[1].isFinite && (-180...180).contains($0[0]) && (-90...90).contains($0[1]) }) else {
            throw DeliveryError(message: "The routing server returned invalid route geometry.")
        }
        try Task.checkCancellation()
        return try SharedBridge.schedule(ScheduleInput(id: UUID().uuidString, created: Date().epoch, start: start, serviceMinutes: settings.serviceMinutes, mode: settings.mode, tour: tour, geometry: geometry, startLocation: location))
    }
}
