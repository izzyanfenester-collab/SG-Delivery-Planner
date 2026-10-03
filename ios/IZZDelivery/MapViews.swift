import SwiftUI
import MapKit

struct LocationPickerView: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var store: AppStore
    @State var selected: StartLocation
    @State private var camera: MapCameraPosition
    @State private var latitude: String
    @State private var longitude: String
    @State private var resolving = false
    let confirm: (StartLocation) -> Void
    init(initial: StartLocation, confirm: @escaping (StartLocation) -> Void) {
        _selected = State(initialValue: initial)
        _camera = State(initialValue: .region(MKCoordinateRegion(center: initial.coordinate, span: .init(latitudeDelta: 0.04, longitudeDelta: 0.04))))
        _latitude = State(initialValue: String(initial.lat)); _longitude = State(initialValue: String(initial.lon)); self.confirm = confirm
    }
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Text("Tap the map to choose a point. Coordinates work even when address lookup is unavailable.").font(.subheadline)
                MapReader { proxy in
                    Map(position: $camera) { Annotation(selected.label, coordinate: selected.coordinate) { Image(systemName: "mappin.circle.fill").font(.largeTitle).foregroundStyle(Brand.gold) } }
                        .onTapGesture { point in if let coordinate = proxy.convert(point, from: .local) { choose(coordinate) } }
                }.frame(height: 350).clipShape(RoundedRectangle(cornerRadius: 20))
                TextField("Location label", text: $selected.label).textFieldStyle(.roundedBorder)
                TextField("Latitude", text: $latitude).textFieldStyle(.roundedBorder).keyboardType(.numbersAndPunctuation)
                TextField("Longitude", text: $longitude).textFieldStyle(.roundedBorder).keyboardType(.numbersAndPunctuation)
                Button("Use coordinates") {
                    guard let lat = Double(latitude), let lon = Double(longitude), (-90...90).contains(lat), (-180...180).contains(lon), lat.isFinite, lon.isFinite else { store.error = "Enter valid latitude and longitude."; return }
                    choose(.init(latitude: lat, longitude: lon)); camera = .region(.init(center: selected.coordinate, span: .init(latitudeDelta: 0.04, longitudeDelta: 0.04)))
                }.buttonStyle(.bordered)
                Text(selected.address).foregroundStyle(.secondary)
                if resolving { ProgressView("Looking up address…") }
                Button("Confirm Location") { guard selected.valid else { store.error = "Enter a location label and valid coordinates."; return }; confirm(selected); dismiss() }.buttonStyle(GoldButton())
            }.padding(20)
        }.screenBackground().navigationTitle("Choose on Map").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
    }
    private func choose(_ coordinate: CLLocationCoordinate2D) {
        selected = StartLocation(type: "CUSTOM", lat: coordinate.latitude, lon: coordinate.longitude, label: "Custom Location", address: "\(coordinate.latitude), \(coordinate.longitude)", postal: "")
        latitude = String(coordinate.latitude); longitude = String(coordinate.longitude); resolving = true
        let captured = coordinate
        CLGeocoder().reverseGeocodeLocation(CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)) { placemarks, _ in
            DispatchQueue.main.async {
                guard selected.lat == captured.latitude && selected.lon == captured.longitude else { return }
                resolving = false
                if let place = placemarks?.first {
                    selected.address = [place.subThoroughfare, place.thoroughfare, place.locality].compactMap { $0 }.joined(separator: " ")
                    selected.postal = place.postalCode ?? ""
                }
            }
        }
    }
}
struct RouteMapView: View {
    let route: Route
    @State private var camera: MapCameraPosition = .automatic
    var body: some View {
        Map(position: $camera) {
            MapPolyline(coordinates: route.coordinates).stroke(Brand.gold, lineWidth: 5)
            Annotation("Start & End: \(route.startLocation.label)", coordinate: route.startLocation.coordinate) { Image(systemName: "flag.checkered.circle.fill").font(.largeTitle).foregroundStyle(Brand.gold) }
            ForEach(route.stops.indices, id: \.self) { index in
                Annotation("\(index+1). \(route.stops[index].place.postal)", coordinate: route.stops[index].place.coordinate) {
                    Text("\(index+1)").font(.headline).padding(10).background(Brand.status(route.stops[index].status), in: Circle())
                        .overlay(Circle().stroke(index == route.current ? Brand.gold : .clear, lineWidth: 4)).accessibilityLabel("Stop \(index+1), \(route.stops[index].statusLabel)")
                }
            }
        }.mapControls { MapCompass(); MapScaleView() }.navigationTitle("Route Map").navigationBarTitleDisplayMode(.inline)
    }
}
