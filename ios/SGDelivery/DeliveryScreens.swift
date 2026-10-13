import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

struct RouteView: View {
    @EnvironmentObject private var store: DeliveryStore

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                SGHeading(title: "Optimized route",
                          subtitle: "Start and end at the selected depot.")
                if let route = store.active {
                    SGCard {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Start & End: \(route.startName) (\(route.startPostal))")
                                .font(.subheadline.bold()).foregroundStyle(SGStyle.gold)
                            Text("Delivery parcels: \(route.orders.count) · Delivered: \(route.deliveredCount)")
                                .font(.subheadline).foregroundStyle(SGStyle.text)
                            Text(String(format: "Driving distance: %.1f km", route.totalKm ?? 0))
                                .foregroundStyle(SGStyle.muted)
                            if let eta = route.returnETA {
                                Text("Estimated return: \(SGFormat.date(eta))")
                                    .foregroundStyle(SGStyle.muted)
                            }
                            Text("ETA excludes live traffic. \(route.trafficMode ?? "Normal") traffic estimate.")
                                .font(.caption).foregroundStyle(SGStyle.muted)
                        }
                    }
                    ForEach(route.orders) { order in
                        NavigationLink {
                            DeliveryDetailView(orderID: order.id)
                                .toolbar(.visible, for: .navigationBar)
                        } label: {
                            SGCard {
                                HStack(alignment: .top, spacing: 12) {
                                    Text("\((route.orders.firstIndex(where: { $0.id == order.id }) ?? 0) + 1)")
                                        .font(.title2.bold()).foregroundStyle(SGStyle.gold)
                                        .frame(width: 32)
                                    VStack(alignment: .leading, spacing: 5) {
                                        HStack {
                                            Text(order.postal).font(.headline).foregroundStyle(SGStyle.text)
                                            Spacer()
                                            SGStatus(status: order.status)
                                        }
                                        Text(order.name.isEmpty ? order.address : order.name)
                                            .foregroundStyle(SGStyle.muted).lineLimit(2)
                                        if let eta = order.arrival {
                                            Text("ETA: \(SGFormat.date(eta))")
                                                .foregroundStyle(SGStyle.blue).font(.caption)
                                        }
                                        Text(String(format: "Leg: %.1f km", order.legKm))
                                            .foregroundStyle(SGStyle.muted).font(.caption)
                                    }
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                } else {
                    SGCard {
                        Text("No optimized route yet. Import WhatsApp orders from Home and tap Optimize route.")
                            .foregroundStyle(SGStyle.muted)
                    }
                }
            }.padding(20)
        }
        .background(SGStyle.navy)
    }
}

struct DeliveryListView: View {
    @EnvironmentObject private var store: DeliveryStore

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                SGHeading(title: "Delivery",
                          subtitle: "Track each customer parcel individually.")
                if let route = store.active {
                    HStack(spacing: 10) {
                        SGMetric(value: "\(route.deliveredCount)", title: "Delivered")
                        SGMetric(value: "\(route.orders.count)", title: "Total parcels")
                    }
                    ProgressView(value: Double(route.deliveredCount), total: Double(max(route.orders.count, 1)))
                        .tint(SGStyle.gold)
                    ForEach(route.orders) { order in
                        NavigationLink {
                            DeliveryDetailView(orderID: order.id)
                                .toolbar(.visible, for: .navigationBar)
                        } label: {
                            HStack(spacing: 12) {
                                Text("\((route.orders.firstIndex(where: { $0.id == order.id }) ?? 0) + 1)")
                                    .font(.headline).foregroundStyle(SGStyle.gold).frame(width: 28)
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(order.postal).font(.headline).foregroundStyle(SGStyle.text)
                                    Text(order.name.isEmpty ? order.address : order.name)
                                        .font(.caption).foregroundStyle(SGStyle.muted).lineLimit(1)
                                    if !order.amount.isEmpty {
                                        Text("$\(order.amount) · \(order.payment)")
                                            .font(.caption).foregroundStyle(SGStyle.blue)
                                    }
                                }
                                Spacer()
                                SGStatus(status: order.status)
                            }
                            .padding(13).frame(maxWidth: .infinity)
                            .background(SGStyle.card, in: RoundedRectangle(cornerRadius: 18))
                        }
                        .buttonStyle(.plain)
                    }
                } else {
                    SGCard { Text("No parcels. Optimize a route first.").foregroundStyle(SGStyle.muted) }
                }
            }.padding(20)
        }
        .background(SGStyle.navy)
    }
}

struct DeliveryDetailView: View {
    @EnvironmentObject private var store: DeliveryStore
    let orderID: UUID
    @State private var holdReason = "Customer not home"
    @State private var holdNote = ""
    @State private var deliveryPhoto: PhotosPickerItem?
    @State private var paymentPhoto: PhotosPickerItem?
    @State private var showNavigation = false
    @State private var showingBuiltInNavigation = false
    @State private var captureKind: ProofCaptureKind?

    private enum ProofCaptureKind: String, Identifiable { case delivery, payment
        var id: String { rawValue }
    }

    private let holdReasons = ["Customer not home", "No answer", "Reschedule",
                               "Payment issue", "Access issue", "Joy buyer", "Other"]
    private var order: DeliveryOrder? {
        store.active?.orders.first(where: { $0.id == orderID })
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let order {
                    SGHeading(title: "Customer delivery", subtitle: "Postal \(order.postal)")
                    customerCard(order)
                    contactCard(order)
                    proofCard(order)
                    reviewCard(order)
                }
            }
            .padding(20)
        }
        .background(SGStyle.navy)
        .navigationTitle(order?.postal ?? "Delivery")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("Choose Navigation", isPresented: $showNavigation,
                            titleVisibility: .visible) {
            if let order {
                Button("Built-in GPS + Voice") { showingBuiltInNavigation = true }
                Button("Apple Maps") { openNavigation(order, provider: "Apple Maps") }
                Button("Google Maps") { openNavigation(order, provider: "Google Maps") }
                Button("Cancel", role: .cancel) {}
            }
        }
        .fullScreenCover(isPresented: $showingBuiltInNavigation) {
            NativeNavigationView(orderID: orderID).environmentObject(store)
        }
        .sheet(item: $captureKind) { kind in
            ProofCameraPicker(onCapture: { image in
                captureKind = nil
                if let encoded = ProofImage.encode(image) {
                    store.saveProof(orderID: orderID, data: encoded, payment: kind == .payment)
                } else { store.errorMessage = "Could not process camera photo." }
            }, onCancel: { captureKind = nil })
                .ignoresSafeArea()
        }
        .onChange(of: deliveryPhoto) { _,next in
            if let next {
                Task { await importPhoto(next, payment: false); deliveryPhoto = nil }
            }
        }
        .onChange(of: paymentPhoto) { _,next in
            if let next {
                Task { await importPhoto(next, payment: true); paymentPhoto = nil }
            }
        }
    }

    private func customerCard(_ order: DeliveryOrder) -> some View {
        SGCard(color: SGStyle.navy, stroke: SGStyle.gold) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 10) {
                    let position = (store.active?.orders.firstIndex(where: { $0.id == order.id }) ?? 0) + 1
                    Text("Stop \(position) of \(store.active?.orders.count ?? 0)")
                        .font(.system(size: 21, weight: .semibold, design: .serif))
                        .foregroundStyle(SGStyle.gold)
                    Spacer()
                    Button("Send message") { openCustomerWhatsApp(order) }
                        .font(.system(size: 14, weight: .semibold, design: .serif))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16).padding(.vertical, 14)
                        .background(Color(red: 24/255, green: 102/255, blue: 145/255),
                                    in: Capsule())
                        .buttonStyle(.plain)
                }
                Text(order.postal).font(.system(size: 30, weight: .bold, design: .rounded))
                    .foregroundStyle(SGStyle.text)
                SGField(title: "Customer", value: order.name)
                SGField(title: "Phone", value: order.phone)
                SGField(title: "Address", value: order.address)
                SGField(title: "Parcel Price (SGD)",
                        value: order.amount.isEmpty ? "—" : "S$\(order.amount)")
                SGField(title: "Payment Mode", value: order.payment)
                SGStatus(status: order.status)
                Divider().overlay(SGStyle.outline)
                if let planned = order.arrival {
                    SGField(title: "Planned arrival", value: SGFormat.date(planned))
                }
                if let updated = order.updatedArrival {
                    SGField(title: "Updated ETA", value: SGFormat.date(updated))
                }
                if let window = DeliveryWhatsApp.etaWindow(for: order) {
                    SGField(title: "WhatsApp ETA window (+10 min)", value: window)
                }
                Text(order.updatedArrival == nil
                    ? "Planning estimate — live Updated ETA not yet available on iOS."
                    : "Updated ETA available for this stop.")
                    .font(.caption2).foregroundStyle(SGStyle.muted)
                if let delivered = order.deliveredAt {
                    SGField(title: "Delivered", value: SGFormat.date(delivered))
                }
                if let reason = order.holdReason {
                    SGField(title: "Hold reason", value: reason)
                }
            }
        }
    }

    private func contactCard(_ order: DeliveryOrder) -> some View {
        SGCard {
            VStack(spacing: 10) {
                SGAction(title: "Navigate", filled: false) { showNavigation = true }
                Text("Send message opens an editable WhatsApp draft for this customer. It never sends automatically.")
                    .font(.caption2).foregroundStyle(SGStyle.muted)
            }
        }
    }

    private func proofCard(_ order: DeliveryOrder) -> some View {
        SGCard {
            VStack(alignment: .leading, spacing: 10) {
                Text("Proof Photos").font(.headline).foregroundStyle(SGStyle.gold)
                Button { captureKind = .delivery } label: {
                    Label("Take Proof of Delivery Photo", systemImage: "camera.fill")
                }.foregroundStyle(SGStyle.gold)
                PhotosPicker(selection: $deliveryPhoto, matching: .images) {
                    Label("Add Proof of Delivery", systemImage: "camera").foregroundStyle(SGStyle.blue)
                }
                Button { captureKind = .payment } label: {
                    Label("Take Proof of Payment Photo", systemImage: "camera.fill")
                }.foregroundStyle(SGStyle.gold)
                PhotosPicker(selection: $paymentPhoto, matching: .images) {
                    Label("Add Proof of Payment", systemImage: "creditcard").foregroundStyle(SGStyle.blue)
                }
                if let encoded = order.proofDelivery,
                   let data = Data(base64Encoded: encoded),
                   let image = UIImage(data: data) {
                    Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 160)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                if let encoded = order.proofPayment,
                   let data = Data(base64Encoded: encoded),
                   let image = UIImage(data: data) {
                    Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 160)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
            }
        }
    }

    private func reviewCard(_ order: DeliveryOrder) -> some View {
        SGCard {
            VStack(alignment: .leading, spacing: 10) {
                Text("Review Delivery").font(.headline).foregroundStyle(SGStyle.gold)
                if order.status == .delivered {
                    Label("Delivered — record locked", systemImage: "checkmark.seal.fill")
                        .foregroundStyle(.green)
                } else {
                    SGAction(title: "Mark Delivered") {
                        store.review(orderID: orderID, status: .delivered)
                    }
                    Picker("Hold Reason", selection: $holdReason) {
                        ForEach(holdReasons, id: \.self) { Text($0) }
                    }.pickerStyle(.menu)
                    if holdReason == "Other" {
                        TextField("Explanation (required)", text: $holdNote)
                            .textFieldStyle(.roundedBorder)
                    }
                    SGAction(title: "On Hold", filled: false) {
                        store.review(orderID: orderID, status: .hold,
                                     reason: holdReason, note: holdNote)
                    }
                    SGAction(title: "Skipped", filled: false) {
                        store.review(orderID: orderID, status: .skipped)
                    }
                }
            }
        }
    }

    private func openCustomerWhatsApp(_ order: DeliveryOrder) {
        guard let links = DeliveryWhatsApp.urls(for: order) else {
            store.errorMessage = DeliveryWhatsApp.missingReason(for: order)
                ?? "Could not prepare WhatsApp message for this customer."
            return
        }
        // Try WhatsApp directly. The HTTPS link is only a fallback when the app is not available.
        UIApplication.shared.open(links.app, options: [:]) { opened in
            guard !opened else { return }
            UIApplication.shared.open(links.web, options: [:]) { webOpened in
                if !webOpened {
                    DispatchQueue.main.async {
                        store.errorMessage = "Could not open WhatsApp. Install WhatsApp or check the customer's phone."
                    }
                }
            }
        }
    }

    private func openNavigation(_ order: DeliveryOrder, provider: String) {
        let coordinate = order.latitude.flatMap { lat in order.longitude.map { lon in "\(lat),\(lon)" } }
        let destination = coordinate ?? order.postal
        let address = provider == "Google Maps"
            ? "https://www.google.com/maps/dir/?api=1&destination=\(destination)&travelmode=driving"
            : "https://maps.apple.com/?daddr=\(destination)&dirflg=d"
        if let url = URL(string: address) { UIApplication.shared.open(url) }
    }

    private func importPhoto(_ item: PhotosPickerItem, payment: Bool) async {
        do {
            guard let data = try await item.loadTransferable(type: Data.self),
                  let image = UIImage(data: data) else {
                store.errorMessage = "Unsupported photo format"; return
            }
            guard let output = ProofImage.encode(image) else {
                store.errorMessage = "Could not compress proof photo"; return
            }
            store.saveProof(orderID: orderID, data: output, payment: payment)
        } catch { store.errorMessage = "Could not load proof photo: \(error.localizedDescription)" }
    }
}

struct HistoryView: View {
    @EnvironmentObject private var store: DeliveryStore
    @State private var restoring = false
    @State private var financeSheet = false
    @State private var androidBackupURL: URL?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                SGHeading(title: "History", subtitle: "Review past delivery routes and backups.")
                SGCard {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Export & Backup").font(.headline).foregroundStyle(SGStyle.gold)
                        SGAction(title: "Delivery Report · Cash · Tax · Excel") {
                            financeSheet = true
                        }
                        SGAction(title: "Prepare Android-Compatible ZIP Backup", filled: false) {
                            androidBackupURL = store.exportAndroidBackup()
                        }
                        if let androidBackupURL {
                            ShareLink(item: androidBackupURL) {
                                Label("Share / Save Android ZIP Backup", systemImage: "archivebox")
                                    .foregroundStyle(SGStyle.blue)
                            }
                        }
                        if let backup = store.exportJSON() {
                            ShareLink(item: backup) {
                                Label("Export iOS Backup (JSON + Photos)", systemImage: "square.and.arrow.up")
                                    .foregroundStyle(SGStyle.blue)
                            }
                        }
                        if let csv = store.exportCSV() {
                            ShareLink(item: csv) {
                                Label("Export Current Route CSV", systemImage: "tablecells")
                                    .foregroundStyle(SGStyle.blue)
                            }
                        }
                        SGAction(title: "Restore Android ZIP / iOS JSON", filled: false) {
                            restoring = true
                        }
                        Text("Android ZIP uses history v1 format and merges missing route IDs. iOS JSON restore REPLACES iOS history. Both include unencrypted private customer data and photos. Back up before restoring.")
                            .font(.caption).foregroundStyle(SGStyle.muted)
                    }
                }
                ForEach(store.routes) { route in
                    Button {
                        store.selectedRouteID = route.id
                    } label: {
                        SGCard(color: SGStyle.cardAlt) {
                            VStack(alignment: .leading, spacing: 8) {
                                HStack {
                                    Text(SGFormat.date(route.created))
                                        .foregroundStyle(SGStyle.text).bold()
                                    Spacer()
                                    if store.selectedRouteID == route.id {
                                        Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                                    }
                                }
                                Text("\(route.orders.count) parcels · \(route.deliveredCount) delivered · \(Int(route.successRate))% success")
                                    .font(.caption).foregroundStyle(SGStyle.muted)
                                Text(String(format: "%.1f km · Return: %@", route.totalKm ?? 0,
                                            route.returnETA.map(SGFormat.date) ?? "—"))
                                    .font(.caption).foregroundStyle(SGStyle.blue)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
                if store.routes.isEmpty {
                    SGCard { Text("No completed or saved delivery routes yet.").foregroundStyle(SGStyle.muted) }
                }
            }.padding(20)
        }
        .background(SGStyle.navy)
        .sheet(isPresented: $financeSheet) {
            NavigationStack { FinanceReportView().environmentObject(store) }
        }
        .fileImporter(isPresented: $restoring, allowedContentTypes: [.json, .zip]) { selection in
            do {
                let url = try selection.get()
                guard url.startAccessingSecurityScopedResource() else {
                    throw DeliveryError.invalid("Cannot access selected backup")
                }
                defer { url.stopAccessingSecurityScopedResource() }
                if url.pathExtension.lowercased() == "zip" {
                    let result = try store.importAndroidBackup(from: url)
                    store.noticeMessage = "Android ZIP restored: (result.restored) routes added; (result.skipped) existing routes skipped."
                } else {
                    try store.restoreJSON(Data(contentsOf: url))
                    store.noticeMessage = "iOS backup restored."
                }
            } catch { store.errorMessage = "Restore failed: \(error.localizedDescription)" }
        }
    }
}
