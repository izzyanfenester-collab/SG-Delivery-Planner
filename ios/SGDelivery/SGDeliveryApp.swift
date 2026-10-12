import SwiftUI
import MapKit
import PhotosUI
import UniformTypeIdentifiers

@main
struct SGDeliveryApp: App {
    @StateObject private var store = DeliveryStore()
    var body: some Scene {
        WindowGroup {
            MainView().environmentObject(store)
                .preferredColorScheme(.dark)
        }
    }
}

struct MainView: View {
    @EnvironmentObject private var store: DeliveryStore
    @State private var selection = 0

    var body: some View {
        TabView(selection: $selection) {
            NavigationStack { PlanView(onPlanned: { selection = 1 }) }
                .tabItem { Label("Home", systemImage: "house.fill") }.tag(0)
            NavigationStack { RouteView() }
                .tabItem { Label("Route", systemImage: "list.number") }.tag(1)
            NavigationStack { DeliveryListView() }
                .tabItem { Label("Delivery", systemImage: "shippingbox.fill") }.tag(2)
            NavigationStack { RouteMapView() }
                .tabItem { Label("Map", systemImage: "map.fill") }.tag(3)
            NavigationStack { HistoryView() }
                .tabItem { Label("History", systemImage: "clock.arrow.circlepath") }.tag(4)
        }
        .tint(.yellow)
        .alert("SG Delivery", isPresented: Binding(
            get: { store.errorMessage != nil },
            set: { if !$0 { store.errorMessage = nil } }
        )) {
            Button("OK") { store.errorMessage = nil }
        } message: {
            Text(store.errorMessage ?? "")
        }
    }
}

struct PlanView: View {
    @EnvironmentObject private var store: DeliveryStore
    let onPlanned: () -> Void
    var body: some View {
        Form {
            Section("Start & End") {
                TextField("Singapore depot postal", text: $store.depotPostal)
                    .keyboardType(.numberPad)
                Text("Default: Woodlands Checkpoint (738203). Route returns here.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Delivery Schedule") {
                DatePicker("Delivery Date", selection: $store.deliveryDate, displayedComponents: .date)
                DatePicker("Start Time (Singapore)", selection: $store.deliveryTime, displayedComponents: .hourAndMinute)
                Stepper("Minutes per order: \(store.minutesPerStop)", value: $store.minutesPerStop, in: 1...120)
            }
            Section("Import WhatsApp Orders / Postal Codes") {
                TextEditor(text: $store.importText)
                    .frame(minHeight: 160)
                    .textInputAutocapitalization(.never)
                let result = store.importPreview()
                HStack {
                    metric("Found", result.found)
                    metric("Added", result.orders.count)
                    metric("Duplicate", result.duplicates)
                    metric("Failed", result.failed)
                }
                Button {
                    UIPasteboard.general.string.map { store.importText = $0 }
                } label: { Label("Paste from WhatsApp", systemImage: "doc.on.clipboard") }
                Button {
                    Task {
                        await store.plan()
                        if store.active?.optimized == true { onPlanned() }
                    }
                } label: {
                    HStack {
                        Spacer()
                        if store.isPlanning { ProgressView().padding(.trailing, 8) }
                        Text(store.isPlanning ? "Optimizing…" : "Optimize Driving Route")
                            .bold()
                        Spacer()
                    }
                }
                .disabled(store.isPlanning || result.orders.isEmpty || result.orders.count > 50)
            }
            Section {
                Link("Singapore Customs — Tax Declare",
                     destination: URL(string: "https://m.customs.gov.sg/CustomsTravellerPortal/")!)
                Text("Route estimates use OneMap and an online OSRM road matrix. They are not live traffic ETAs. Internet is required to optimize.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("SG Delivery V3")
    }
    private func metric(_ label: String, _ value: Int) -> some View {
        VStack { Text("\(value)").font(.headline); Text(label).font(.system(size: 10)) }
            .frame(maxWidth: .infinity)
    }
}

struct RouteView: View {
    @EnvironmentObject private var store: DeliveryStore
    var body: some View {
        List {
            if let route = store.active {
                Section {
                    Text("Start & End: \(route.startName) (\(route.startPostal))")
                    Text("Stops: \(route.orders.count) • Delivered: \(route.deliveredCount)")
                    if let km = route.totalKm { Text(String(format: "Road distance: %.1f km", km)) }
                    if let eta = route.returnETA { Text("Estimated return: \(SGFormat.date(eta))") }
                }
                Section("Optimized Stops") {
                    ForEach(Array(route.orders.enumerated()), id: \.element.id) { index,order in
                        NavigationLink {
                            DeliveryDetailView(orderID: order.id)
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text("\(index + 1). \(order.postal)").bold()
                                    Spacer()
                                    Text(order.status.rawValue).foregroundStyle(order.status == .delivered ? .green : .secondary)
                                }
                                Text(order.name.isEmpty ? order.address : order.name)
                                    .font(.subheadline).lineLimit(2)
                                if let arrival = order.arrival {
                                    Text("ETA: \(SGFormat.date(arrival))").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            } else {
                ContentUnavailableView("No Route Yet", systemImage: "point.topleft.down.curvedto.point.bottomright.up",
                                       description: Text("Import WhatsApp orders on Home to optimize."))
            }
        }.navigationTitle("Route")
    }
}

struct DeliveryListView: View {
    @EnvironmentObject private var store: DeliveryStore
    var body: some View {
        List {
            if let route = store.active {
                Section {
                    Text("\(route.deliveredCount) / \(route.orders.count) delivered")
                    ProgressView(value: Double(route.deliveredCount), total: Double(max(route.orders.count, 1)))
                }
                ForEach(Array(route.orders.enumerated()), id: \.element.id) { index,order in
                    NavigationLink {
                        DeliveryDetailView(orderID: order.id)
                    } label: {
                        HStack {
                            Text("\(index+1)").foregroundStyle(.yellow).frame(width: 25)
                            VStack(alignment: .leading) {
                                Text(order.postal).bold()
                                Text(order.name.isEmpty ? order.address : order.name)
                                    .font(.caption).lineLimit(1)
                            }
                            Spacer()
                            Text(order.status.rawValue).font(.caption)
                        }
                    }
                }
            } else { ContentUnavailableView("No Deliveries", systemImage: "shippingbox") }
        }.navigationTitle("Delivery")
    }
}

struct DeliveryDetailView: View {
    @EnvironmentObject private var store: DeliveryStore
    let orderID: UUID
    @State private var holdReason = "Customer not home"
    @State private var holdNote = ""
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var photoPayment = false

    private var order: DeliveryOrder? {
        store.active?.orders.first(where: { $0.id == orderID })
    }
    var body: some View {
        Form {
            if let order {
                Section("Customer") {
                    LabeledContent("Postal", value: order.postal)
                    LabeledContent("Name", value: order.name.isEmpty ? "—" : order.name)
                    Text(order.address)
                    LabeledContent("Phone", value: order.phone.isEmpty ? "—" : order.phone)
                    LabeledContent("Price SGD", value: order.amount.isEmpty ? "—" : "$\(order.amount)")
                    LabeledContent("Payment", value: order.payment.isEmpty ? "—" : order.payment)
                    LabeledContent("Status", value: order.status.rawValue)
                    if let arrival = order.arrival { Text("Planned arrival: \(SGFormat.date(arrival))") }
                    if let delivered = order.deliveredAt { Text("Delivered: \(SGFormat.date(delivered))") }
                }
                Section("Customer Contact & Navigation") {
                    if let url = URL(string: "https://www.google.com/maps/search/?api=1&query=\(order.postal)") {
                        Link("Navigate with Google Maps", destination: url)
                    }
                    if !order.phone.isEmpty {
                        let digits = order.phone.filter(\.isNumber)
                        let text = order.originalMessage.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
                        if let url = URL(string: "https://wa.me/\(digits)?text=\(text)") {
                            Link("Open WhatsApp Draft (does not send)", destination: url)
                        }
                    }
                }
                Section("Proof Photos") {
                    PhotosPicker(selection: $selectedPhoto, matching: .images) {
                        Label("Add Proof of Delivery", systemImage: "camera")
                    }.simultaneousGesture(TapGesture().onEnded { photoPayment = false })
                    PhotosPicker(selection: $selectedPhoto, matching: .images) {
                        Label("Add Proof of Payment", systemImage: "creditcard")
                    }.simultaneousGesture(TapGesture().onEnded { photoPayment = true })
                    if let data = order.proofDelivery.flatMap(Data.init(base64Encoded:)),
                       let image = UIImage(data: data) {
                        Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 160)
                    }
                    if let data = order.proofPayment.flatMap(Data.init(base64Encoded:)),
                       let image = UIImage(data: data) {
                        Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 160)
                    }
                }
                Section("Review Delivery") {
                    if order.status == .delivered {
                        Label("Delivered — record locked", systemImage: "checkmark.seal.fill").foregroundStyle(.green)
                    } else {
                        Button("Mark Delivered") { store.review(orderID: orderID, status: .delivered) }
                            .foregroundStyle(.green)
                        Picker("Hold Reason", selection: $holdReason) {
                            ForEach(["Customer not home","No answer","Reschedule","Payment issue","Access issue","Joy buyer","Other"], id: \.self) { Text($0) }
                        }
                        if holdReason == "Other" { TextField("Explanation (required)", text: $holdNote) }
                        Button("On Hold") { store.review(orderID: orderID, status: .hold, reason: holdReason, note: holdNote) }
                            .foregroundStyle(.orange)
                        Button("Skipped") { store.review(orderID: orderID, status: .skipped) }
                    }
                }
            }
        }
        .navigationTitle(order?.postal ?? "Delivery")
        .onChange(of: selectedPhoto) { _,newValue in
            guard let newValue else { return }
            Task {
                do {
                    if let data = try await newValue.loadTransferable(type: Data.self),
                       let image = UIImage(data: data),
                       let compressed = image.jpegData(compressionQuality: 0.45) {
                        store.saveProof(orderID: orderID, data: compressed, payment: photoPayment)
                    }
                } catch { store.errorMessage = "Could not load proof photo" }
                selectedPhoto = nil
            }
        }
    }
}

struct RouteMapView: View {
    @EnvironmentObject private var store: DeliveryStore
    @State private var position: MapCameraPosition = .region(MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 1.3521, longitude: 103.8198),
        span: MKCoordinateSpan(latitudeDelta: 0.35, longitudeDelta: 0.35)))
    var body: some View {
        Map(position: $position) {
            if let route = store.active {
                ForEach(route.orders) { order in
                    if let lat = order.latitude, let lon = order.longitude {
                        Annotation(order.postal, coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon)) {
                            Image(systemName: order.status == .delivered ? "checkmark.circle.fill" : "shippingbox.circle.fill")
                                .font(.title2).foregroundStyle(order.status == .delivered ? .green : .yellow)
                        }
                    }
                }
                Annotation("Start & End", coordinate: CLLocationCoordinate2D(
                    latitude: 1.4464, longitude: 103.7696)) {
                    Image(systemName: "house.circle.fill").font(.title).foregroundStyle(.cyan)
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            Text("Pins show delivery locations. Driving polyline and live navigation are not yet available.")
                .font(.caption).padding(10).background(.regularMaterial)
        }
        .navigationTitle("Delivery Map")
    }
}

struct HistoryView: View {
    @EnvironmentObject private var store: DeliveryStore
    @State private var restoreFile = false
    var body: some View {
        List {
            Section("Backup") {
                if let url = store.exportJSON() {
                    ShareLink(item: url) { Label("Export iOS Backup (JSON + Photos)", systemImage: "square.and.arrow.up") }
                }
                Button("Restore iOS Backup") { restoreFile = true }
                Text("Backups contain private customer data and embedded proof photos. JSON is not encrypted.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Saved Routes") {
                ForEach(store.routes) { route in
                    Button {
                        store.selectedRouteID = route.id
                    } label: {
                        VStack(alignment: .leading) {
                            Text(SGFormat.date(route.created)).bold()
                            Text("\(route.orders.count) parcels • \(route.deliveredCount) delivered • \(Int(route.successRate))% success")
                            if let km = route.totalKm { Text(String(format: "%.1f km", km)) }
                            if store.selectedRouteID == route.id {
                                Label("Selected", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("History")
        .fileImporter(isPresented: $restoreFile, allowedContentTypes: [.json]) { result in
            do {
                let url = try result.get()
                guard url.startAccessingSecurityScopedResource() else {
                    throw DeliveryError.invalid("Could not access selected backup")
                }
                defer { url.stopAccessingSecurityScopedResource() }
                try store.restoreJSON(Data(contentsOf: url))
            } catch { store.errorMessage = "Restore failed: \(error.localizedDescription)" }
        }
    }
}
