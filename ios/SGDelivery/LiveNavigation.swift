import SwiftUI
import MapKit
import CoreLocation
import AVFoundation

/// Foreground-only native turn guidance. GPS remains OFF when this screen is closed.
/// Apple MapKit supplies road-following geometry, traffic-aware expected travel time and steps.
@MainActor
final class LiveNavigationSession: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var position: CLLocationCoordinate2D?
    @Published var road: [CLLocationCoordinate2D] = []
    @Published var nextInstruction = "Waiting for GPS..."
    @Published var eta: Date?
    @Published var remainingMeters = 0.0
    @Published var warning: String?
    @Published var isRouting = false
    @Published var authorized = false

    private let manager = CLLocationManager()
    private let voice = AVSpeechSynthesizer()
    private var destination: CLLocationCoordinate2D?
    private var activeRoute: MKRoute?
    private var progressStarted: Date?
    private var lastRoutingPosition: CLLocationCoordinate2D?
    private var lastRequest: Date = .distantPast
    private var lastSpokenStep = -1
    private var callback: ((Date) -> Void)?
    private var calculateTask: Task<Void, Never>?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = 15
        manager.activityType = .automotiveNavigation
        manager.pausesLocationUpdatesAutomatically = false
    }

    func start(for order: DeliveryOrder, update: @escaping (Date) -> Void) {
        guard let lat = order.latitude, let lon = order.longitude,
              CLLocationCoordinate2DIsValid(CLLocationCoordinate2D(latitude: lat, longitude: lon)) else {
            warning = "Missing destination coordinates. Optimize the route again."
            return
        }
        destination = CLLocationCoordinate2D(latitude: lat, longitude: lon)
        callback = update
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            authorized = true
            manager.startUpdatingLocation()
            if let location = manager.location, location.horizontalAccuracy >= 0,
               location.horizontalAccuracy <= 70 { accept(location) }
        default: warning = "Location access denied. Enable While Using the App in iPhone Settings."
        }
    }

    func stop() {
        manager.stopUpdatingLocation()
        calculateTask?.cancel()
        calculateTask = nil
        voice.stopSpeaking(at: .immediate)
        callback = nil
        destination = nil
        activeRoute = nil
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedWhenInUse ||
           manager.authorizationStatus == .authorizedAlways {
            authorized = true
            if destination != nil { manager.startUpdatingLocation() }
        } else if manager.authorizationStatus == .denied || manager.authorizationStatus == .restricted {
            warning = "Location permission unavailable. Change it in iPhone Settings."
            authorized = false
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard destination != nil, let location = locations.last else { return }
        accept(location)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        warning = "GPS unavailable: \(error.localizedDescription)"
    }

    private func accept(_ location: CLLocation) {
        guard location.horizontalAccuracy >= 0, location.horizontalAccuracy <= 70,
              abs(location.timestamp.timeIntervalSinceNow) < 20,
              let target = destination else { return }
        position = location.coordinate
        let distanceToTarget = location.distance(from: CLLocation(latitude: target.latitude, longitude: target.longitude))
        if distanceToTarget < 55 {
            remainingMeters = 0
            eta = Date()
            callback?(Date())
            nextInstruction = "You are near the delivery address. Park safely."
            return
        }
        if let route = activeRoute {
            let approximate = NavigationMath.remainingRoadMeters(
                current: location.coordinate, path: road)
            if let meters = approximate {
                remainingMeters = meters
                // The original MapKit expected duration is updated using remaining on-road distance.
                // Refresh from MapKit after moving 200 m or after 75 seconds, to correct congestion/off-route.
                let duration = NavigationMath.remainingSeconds(
                    roadMeters: meters, routeMeters: route.distance,
                    routedSeconds: route.expectedTravelTime)
                let newETA = Date().addingTimeInterval(duration)
                eta = newETA
                callback?(newETA)
            }
            announceUpcomingStep(location: location, route: route)
            let moved = lastRoutingPosition.map {
                CLLocation(latitude: $0.latitude, longitude: $0.longitude).distance(from: location)
            } ?? .greatestFiniteMagnitude
            let offRoad = NavigationMath.distanceFromRoad(current: location.coordinate, path: road) ?? 0
            if moved > 200 || Date().timeIntervalSince(lastRequest) > 75 || offRoad > 120 {
                requestDirections(from: location.coordinate)
            }
        } else if !isRouting {
            requestDirections(from: location.coordinate)
        }
    }

    private func requestDirections(from origin: CLLocationCoordinate2D) {
        guard let destination, !isRouting, Date().timeIntervalSince(lastRequest) > 20 else { return }
        lastRequest = Date()
        lastRoutingPosition = origin
        isRouting = true
        let request = MKDirections.Request()
        request.source = MKMapItem(placemark: MKPlacemark(coordinate: origin))
        request.destination = MKMapItem(placemark: MKPlacemark(coordinate: destination))
        request.transportType = .automobile
        request.requestsAlternateRoutes = false
        let directions = MKDirections(request: request)
        calculateTask?.cancel()
        calculateTask = Task { [weak self] in
            guard let self else { return }
            defer { self.isRouting = false }
            do {
                let response = try await directions.calculate()
                guard !Task.isCancelled, let route = response.routes.first else { return }
                self.activeRoute = route
                var coordinates = [CLLocationCoordinate2D](
                    repeating: CLLocationCoordinate2D(), count: route.polyline.pointCount)
                route.polyline.getCoordinates(&coordinates, range: NSRange(location: 0, length: coordinates.count))
                self.road = coordinates
                self.lastSpokenStep = -1
                self.progressStarted = Date()
                self.remainingMeters = route.distance
                self.eta = Date().addingTimeInterval(route.expectedTravelTime)
                if let eta = self.eta { self.callback?(eta) }
                self.nextInstruction = route.steps.first(where: { !$0.instructions.isEmpty })?.instructions
                    ?? "Continue towards the selected delivery address."
                self.warning = nil
                self.speak("Navigation started. \(self.nextInstruction)")
            } catch {
                guard !Task.isCancelled else { return }
                self.warning = "Apple Maps routing unavailable: \(error.localizedDescription). Try external Maps."
            }
        }
    }

    private func announceUpcomingStep(location: CLLocation, route: MKRoute) {
        for (index, step) in route.steps.enumerated() where index > lastSpokenStep && !step.instructions.isEmpty {
            let coordinate = step.polyline.coordinate
            let delta = location.distance(from: CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude))
            if delta < 90 {
                lastSpokenStep = index
                nextInstruction = step.instructions
                speak(step.instructions)
                break
            }
        }
    }

    private func speak(_ message: String) {
        guard !message.isEmpty else { return }
        voice.stopSpeaking(at: .immediate)
        let speech = AVSpeechUtterance(string: message)
        speech.voice = AVSpeechSynthesisVoice(language: "en-SG")
            ?? AVSpeechSynthesisVoice(language: "en-US")
        speech.rate = 0.48
        voice.speak(speech)
    }

    func repeatInstruction() { speak(nextInstruction) }
}

/// Pure calculations shared with iOS smoke tests. Estimates are NOT a guaranteed delivery time.
enum NavigationMath {
    static func remainingSeconds(roadMeters: Double, routeMeters: Double, routedSeconds: Double) -> Double {
        guard roadMeters.isFinite, routeMeters.isFinite, routedSeconds.isFinite,
              routeMeters > 0, routedSeconds >= 0 else { return 0 }
        return max(0, routedSeconds * min(1.5, max(0, roadMeters / routeMeters)))
    }

    static func remainingRoadMeters(current: CLLocationCoordinate2D,
                                    path: [CLLocationCoordinate2D]) -> Double? {
        guard path.count > 1 else { return nil }
        guard let nearest = path.indices.min(by: {
            MKMapPoint(path[$0]).distance(to: MKMapPoint(current))
            < MKMapPoint(path[$1]).distance(to: MKMapPoint(current))
        }) else { return nil }
        if nearest >= path.count - 1 { return 0 }
        return zip(path[nearest...].dropLast(), path[(nearest + 1)...])
            .reduce(0) { $0 + CLLocation(latitude: $1.0.latitude, longitude: $1.0.longitude)
                .distance(from: CLLocation(latitude: $1.1.latitude, longitude: $1.1.longitude)) }
    }

    static func distanceFromRoad(current: CLLocationCoordinate2D,
                                 path: [CLLocationCoordinate2D]) -> Double? {
        guard !path.isEmpty else { return nil }
        return path.map { MKMapPoint($0).distance(to: MKMapPoint(current)) }.min()
    }
}

struct NativeNavigationView: View {
    @EnvironmentObject private var store: DeliveryStore
    @Environment(\.dismiss) private var dismiss
    let orderID: UUID
    @StateObject private var navigator = LiveNavigationSession()
    @State private var mapPosition = MapCameraPosition.userLocation(fallback: .automatic)
    private var order: DeliveryOrder? { store.active?.orders.first(where: { $0.id == orderID }) }

    var body: some View {
        VStack(spacing: 0) {
            Map(position: $mapPosition) {
                UserAnnotation()
                if navigator.road.count > 1 {
                    MapPolyline(coordinates: navigator.road).stroke(SGStyle.blue, lineWidth: 7)
                }
                if let lat = order?.latitude, let lon = order?.longitude {
                    Annotation("Delivery", coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon)) {
                        Image(systemName: "shippingbox.fill")
                            .padding(12).background(SGStyle.gold, in: Circle())
                    }
                }
            }
            .mapStyle(.standard(elevation: .flat, emphasis: .automatic))
            .mapControls {
                MapUserLocationButton()
                MapCompass()
            }
            SGCard {
                VStack(alignment: .leading, spacing: 10) {
                    Text(order.map { "\($0.postal) · \($0.name)" } ?? "Delivery")
                        .foregroundStyle(SGStyle.gold).font(.headline)
                    Text(navigator.nextInstruction).foregroundStyle(SGStyle.text)
                        .font(.title3.bold())
                    HStack {
                        Text(navigator.eta.map { "Updated ETA: \(DeliveryWhatsApp.clock($0))" }
                             ?? "Waiting for live route...")
                        Spacer()
                        Text(String(format: "%.1f km", navigator.remainingMeters / 1000))
                    }.foregroundStyle(SGStyle.blue).font(.caption.bold())
                    if let warning = navigator.warning {
                        Text(warning).foregroundStyle(.orange).font(.caption)
                    }
                    HStack {
                        Button("Repeat voice") { navigator.repeatInstruction() }
                            .buttonStyle(.bordered)
                        Spacer()
                        Button("End navigation") { dismiss() }
                            .buttonStyle(.borderedProminent)
                    }
                    Text("Foreground GPS guidance · Apple MapKit directions · traffic estimate. Follow road signs and never interact while driving.")
                        .font(.caption2).foregroundStyle(SGStyle.muted)
                }
            }.padding(10).background(SGStyle.navy)
        }
        .background(SGStyle.navy)
        .onAppear {
            if let order {
                navigator.start(for: order) { eta in
                    store.updateLiveETA(orderID: order.id, value: eta)
                }
            }
        }
        .onDisappear { navigator.stop() }
    }
}
