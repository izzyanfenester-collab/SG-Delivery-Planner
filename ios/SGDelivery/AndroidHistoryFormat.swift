import Foundation

/// Translation between Android's Gson SavedRoute/Plan JSON and SwiftUI delivery records.
/// A backup is NOT accepted unless every route and parcel can be validated.
enum AndroidHistoryFormat {
    static func fromLocal(_ string: String?) -> Date? {
        guard let string, !string.isEmpty else { return nil }
        let formats = [
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS",
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm"
        ]
        for format in formats {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.timeZone = SGFormat.singapore
            f.dateFormat = format
            if let date = f.date(from: string) { return date }
        }
        return nil
    }

    static func local(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = SGFormat.singapore
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        return f.string(from: date)
    }

    static func string(_ any: Any?) -> String? {
        switch any {
        case let text as String: return text
        case let number as NSNumber: return number.stringValue
        default: return nil
        }
    }
    static func number(_ any: Any?) -> Double? {
        guard let raw = string(any), let value = Double(raw), value.isFinite else { return nil }
        return value
    }

    static func readRoutes(_ routesData: Data, photos: [String: Data] = [:]) throws -> [DeliveryRoute] {
        guard routesData.count <= 30 * 1024 * 1024,
              let rows = try JSONSerialization.jsonObject(with: routesData) as? [[String: Any]],
              rows.count <= 2000 else {
            throw DeliveryError.invalid("Android routes.json is invalid or too large.")
        }
        var existing = Set<UUID>()
        var routes: [DeliveryRoute] = []
        for row in rows {
            guard let id = string(row["id"]).flatMap(UUID.init(uuidString:)),
                  existing.insert(id).inserted,
                  let json = row["json"] as? String,
                  let planBytes = json.data(using: .utf8),
                  let plan = try JSONSerialization.jsonObject(with: planBytes) as? [String: Any],
                  string(plan["id"]) == id.uuidString.lowercased() ||
                  string(plan["id"]) == id.uuidString ||
                  string(plan["id"])?.lowercased() == id.uuidString.lowercased()
            else { throw DeliveryError.invalid("Android backup has invalid or mismatched route IDs.") }

            guard let scheduled = fromLocal(string(plan["start"])),
                  let rawStops = plan["stops"] as? [[String: Any]],
                  rawStops.count <= 50, !rawStops.isEmpty else {
                throw DeliveryError.invalid("Android route has missing start time or invalid stops.")
            }
            let created = fromLocal(string(row["created"])) ?? fromLocal(string(plan["created"])) ?? scheduled
            let startLocation = plan["startLocation"] as? [String: Any] ?? [:]
            let startLatitude = number(startLocation["latitude"]) ?? 1.4464
            let startLongitude = number(startLocation["longitude"]) ?? 103.7696
            guard (-90...90).contains(startLatitude), (-180...180).contains(startLongitude) else {
                throw DeliveryError.invalid("Android backup contains an invalid depot.")
            }
            let postal = string(startLocation["postalCode"])?.isEmpty == false ?
                string(startLocation["postalCode"])! : "738203"

            var importedOrders: [DeliveryOrder] = []
            var seenOrders = Set<UUID>()
            var travelled = 0.0
            for item in rawStops {
                guard let place = item["place"] as? [String: Any],
                      let code = string(place["postal"]),
                      code.range(of: #"^[0-9]{6}$"#, options: .regularExpression) != nil,
                      let coordinateLat = number(place["lat"]),
                      let coordinateLon = number(place["lon"]),
                      (-90...90).contains(coordinateLat), (-180...180).contains(coordinateLon),
                      let arrival = fromLocal(string(item["arrival"])),
                      let departure = fromLocal(string(item["leave"])) else {
                    throw DeliveryError.invalid("Android backup contains an invalid postal, coordinate, or ETA.")
                }
                let customer = item["order"] as? [String: Any] ?? [:]
                guard let parcelID = (string(item["orderId"]) ?? string(customer["orderId"]))
                    .flatMap(UUID.init(uuidString:)), seenOrders.insert(parcelID).inserted else {
                    throw DeliveryError.invalid("Android route contains duplicate/invalid parcel IDs.")
                }
                let leg = item["leg"] as? [String: Any] ?? [:]
                let distance = number(leg["km"]) ?? 0
                let seconds = number(leg["baseSeconds"]) ?? 0
                guard distance >= 0, seconds >= 0 else {
                    throw DeliveryError.invalid("Invalid distance/duration in Android backup.")
                }
                travelled += distance
                let rawStatus = (string(item["status"]) ?? "PENDING").uppercased()
                let status: DeliveryStatus
                switch rawStatus {
                case "DELIVERED", "COMPLETED": status = .delivered
                case "ON_HOLD", "ON HOLD": status = .hold
                case "SKIPPED": status = .skipped
                default: status = .pending
                }
                let deliveryProofName = string(customer["proofFileId"])
                let paymentProofName = string(customer["paymentProofFileId"])
                importedOrders.append(DeliveryOrder(
                    id: parcelID,
                    postal: code,
                    name: string(customer["customerName"]) ?? "",
                    phone: string(customer["phoneNumber"]) ?? "",
                    address: string(customer["fullAddress"]) ?? string(place["address"]) ?? "",
                    amount: string(customer["parcelPrice"]) ?? "",
                    payment: string(customer["paymentStatus"]) ?? "",
                    originalMessage: string(customer["originalMessage"]) ?? "",
                    status: status,
                    reviewedAt: fromLocal(string(item["reviewedAt"])),
                    deliveredAt: fromLocal(string(item["completedAt"])),
                    holdReason: string(item["holdReason"]),
                    holdNote: string(item["holdNote"]),
                    arrival: arrival,
                    updatedArrival: fromLocal(string(item["etaArrival"])),
                    leave: departure,
                    latitude: coordinateLat,
                    longitude: coordinateLon,
                    legKm: distance,
                    legSeconds: seconds,
                    proofDelivery: deliveryProofName.flatMap { photos[$0]?.base64EncodedString() },
                    proofPayment: paymentProofName.flatMap { photos[$0]?.base64EncodedString() }
                ))
            }
            let service = Int(number(plan["serviceMinutes"]) ?? 8)
            let geometry = (plan["geometry"] as? [[Double]])?.compactMap { pair -> RouteCoordinate? in
                guard pair.count >= 2, (-90...90).contains(pair[1]), (-180...180).contains(pair[0]) else { return nil }
                return RouteCoordinate(latitude: pair[1], longitude: pair[0])
            }
            let total = travelled + (number((plan["returnLeg"] as? [String: Any])?["km"]) ?? 0)
            routes.append(DeliveryRoute(
                id: id, created: created,
                date: scheduled, startTime: scheduled,
                startName: string(startLocation["label"]) ?? "Woodlands Checkpoint",
                startPostal: postal,
                depotLatitude: startLatitude, depotLongitude: startLongitude,
                minutesPerStop: service, orders: importedOrders,
                totalKm: total,
                returnETA: fromLocal(string(plan["etaReturned"])) ??
                    fromLocal(string(plan["returned"])),
                optimized: true, roadGeometry: geometry,
                trafficMode: string(plan["mode"]),
                cashOnHand: string(plan["cashOnHand"]),
                tax: string(plan["tax"]),
                exchangeRate: string(plan["exchangeRate"]),
                remark: string(plan["remark"])
            ))
        }
        return routes
    }

    /// Android expects route JSON stored inside a SavedRoute array, not as nested objects.
    static func exportRows(_ routes: [DeliveryRoute]) throws -> (routesData: Data, photos: [String: Data]) {
        guard routes.count <= 2000 else { throw DeliveryError.invalid("Too many routes.") }
        var rows: [[String: Any]] = []
        var photoFiles: [String: Data] = [:]
        for route in routes {
            guard !route.orders.isEmpty, route.orders.count <= 50 else { continue }
            let id = route.id.uuidString.lowercased()
            let start = local(route.startTime)
            var cumulative = 0.0
            var stopJSON: [[String: Any]] = []
            for item in route.orders {
                cumulative += item.legKm
                let orderID = item.id.uuidString.lowercased()
                var customer: [String: Any] = [
                    "orderId": orderID, "postalCode": item.postal,
                    "customerName": item.name, "phoneNumber": item.phone,
                    "fullAddress": item.address, "paymentStatus": item.payment.uppercased(),
                    "originalMessage": item.originalMessage
                ]
                if let amount = MoneyCalculator.amount(item.amount) {
                    customer["parcelPrice"] = NSDecimalNumber(decimal: amount)
                }
                if let encoded = item.proofDelivery, let data = Data(base64Encoded: encoded),
                   data.count <= 20 * 1024 * 1024 {
                    let file = "delivery_\(orderID).jpg"
                    customer["proofFileId"] = file
                    photoFiles[file] = data
                }
                if let encoded = item.proofPayment, let data = Data(base64Encoded: encoded),
                   data.count <= 20 * 1024 * 1024 {
                    let file = "payment_\(orderID).jpg"
                    customer["paymentProofFileId"] = file
                    photoFiles[file] = data
                }
                let arrival = item.arrival ?? route.startTime
                let leave = item.leave ?? arrival.addingTimeInterval(Double(route.minutesPerStop) * 60)
                var stop: [String: Any] = [
                    "place": ["postal": item.postal, "lat": item.latitude ?? 1.3521,
                              "lon": item.longitude ?? 103.8198, "block": "",
                              "area": "", "address": item.address],
                    "leg": ["km": item.legKm, "baseSeconds": item.legSeconds, "bufferSeconds": 0],
                    "arrival": local(arrival), "leave": local(leave),
                    "cumulativeKm": cumulative,
                    "status": item.status == .delivered ? "DELIVERED" :
                       item.status == .hold ? "ON_HOLD" :
                       item.status == .skipped ? "SKIPPED" : "PENDING",
                    "orderId": orderID, "order": customer
                ]
                if let moment = item.deliveredAt { stop["completedAt"] = local(moment) }
                if let moment = item.reviewedAt { stop["reviewedAt"] = local(moment) }
                if let note = item.holdNote { stop["holdNote"] = note }
                if let reason = item.holdReason { stop["holdReason"] = reason }
                if let moment = item.updatedArrival { stop["etaArrival"] = local(moment) }
                stopJSON.append(stop)
            }
            let depot = [
                "type": route.startPostal == "738203" ? "WOODLANDS" : "CUSTOM",
                "latitude": route.depotLatitude ?? 1.4464,
                "longitude": route.depotLongitude ?? 103.7696,
                "label": route.startName,
                "address": route.startName,
                "postalCode": route.startPostal
            ] as [String: Any]
            let geometry = (route.roadGeometry ?? []).map { [$0.longitude, $0.latitude] }
            let finish = route.returnETA ?? route.orders.last?.leave ?? route.startTime
            let reviewed = route.orders.firstIndex(where: { $0.reviewedAt == nil }) ?? route.orders.count
            var plan: [String: Any] = [
                "id": id, "created": local(route.created), "start": start,
                "serviceMinutes": route.minutesPerStop, "mode": route.trafficMode ?? "Normal",
                "stops": stopJSON, "returnLeg": ["km": max(0, (route.totalKm ?? cumulative) - cumulative),
                "baseSeconds": 0, "bufferSeconds": 0],
                "returned": local(finish), "geometry": geometry, "current": reviewed,
                "cashOnHand": route.cashOnHand ?? "0.00",
                "tax": route.tax ?? "0.00", "exchangeRate": route.exchangeRate ?? "3.60",
                "remark": route.remark ?? "", "startLocation": depot, "deliveryStateVersion": 2
            ]
            if let actual = route.orders.compactMap(\.deliveredAt).max(),
               route.orders.allSatisfy({ $0.status == .delivered }) {
                plan["actualCompletion"] = local(actual)
            }
            let data = try JSONSerialization.data(withJSONObject: plan, options: [.sortedKeys])
            guard let json = String(data: data, encoding: .utf8) else {
                throw DeliveryError.invalid("Could not encode Android route.")
            }
            rows.append(["id": id, "json": json, "created": local(route.created)])
        }
        return (try JSONSerialization.data(withJSONObject: rows, options: [.sortedKeys]), photoFiles)
    }
}
