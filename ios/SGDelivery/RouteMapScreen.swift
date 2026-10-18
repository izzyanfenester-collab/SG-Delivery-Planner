import SwiftUI
import MapKit

struct RouteMapView: View {
    @EnvironmentObject private var store: DeliveryStore
    @State private var position: MapCameraPosition = .region(
        MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 1.3521, longitude: 103.8198),
                           span: MKCoordinateSpan(latitudeDelta: 0.35, longitudeDelta: 0.35)))

    var body: some View {
        VStack(spacing: 0) {
            Map(position: $position) {
                if let route = store.active {
                    if let geometry = route.roadGeometry, geometry.count >= 2 {
                        MapPolyline(coordinates: geometry.map {
                            CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
                        })
                        .stroke(SGStyle.blue, lineWidth: 5)
                    }
                    ForEach(route.orders) { order in
                        if let lat = order.latitude, let lon = order.longitude {
                            Annotation(order.postal, coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon)) {
                                VStack(spacing: 2) {
                                    Image(systemName: order.status == .delivered
                                          ? "checkmark.circle.fill" : "shippingbox.circle.fill")
                                        .font(.title2)
                                        .foregroundStyle(order.status == .delivered ? .green : SGStyle.gold)
                                    Text(order.postal).font(.system(size: 10, weight: .bold))
                                        .foregroundStyle(SGStyle.navy)
                                        .padding(3)
                                        .background(.white.opacity(0.92), in: RoundedRectangle(cornerRadius: 4))
                                }
                            }
                        }
                    }
                    Annotation("Start & End", coordinate: CLLocationCoordinate2D(
                        latitude: route.depotLatitude ?? 1.4464,
                        longitude: route.depotLongitude ?? 103.7696)) {
                        Image(systemName: "house.circle.fill")
                            .font(.largeTitle).foregroundStyle(.cyan)
                    }
                }
            }
            .mapStyle(.standard(elevation: .flat))
            HStack(spacing: 10) {
                Image(systemName: "map.fill").foregroundStyle(SGStyle.gold)
                Text(store.active?.roadGeometry?.isEmpty == false
                     ? "Road route and postal stops. Planned ETA is not live traffic."
                     : "Delivery pins shown. Routing polyline may be unavailable.")
                    .font(.caption).foregroundStyle(SGStyle.muted)
                Spacer()
            }
            .padding(12)
            .background(SGStyle.navy)
        }
        .onAppear { focusOnRoute() }
        .onChange(of: store.selectedRouteID) { _,_ in focusOnRoute() }
        .background(SGStyle.navy)
    }
    private func focusOnRoute() {
        guard let route = store.active else { return }
        let points = route.orders.compactMap { item -> CLLocationCoordinate2D? in
            guard let lat = item.latitude, let lon = item.longitude else { return nil }
            return CLLocationCoordinate2D(latitude: lat, longitude: lon)
        }
        guard !points.isEmpty else { return }
        let lats = points.map(\.latitude) + [route.depotLatitude ?? 1.4464]
        let lons = points.map(\.longitude) + [route.depotLongitude ?? 103.7696]
        guard let minLat = lats.min(), let maxLat = lats.max(),
              let minLon = lons.min(), let maxLon = lons.max() else { return }
        position = .region(MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: (minLat + maxLat) / 2, longitude: (minLon + maxLon) / 2),
            span: MKCoordinateSpan(latitudeDelta: max(0.06, (maxLat-minLat)*1.35),
                                   longitudeDelta: max(0.06, (maxLon-minLon)*1.35))))
    }
}
