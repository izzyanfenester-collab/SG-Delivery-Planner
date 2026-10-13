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

    private let holdReasons = ["Customer not home", "No answer", "Reschedule",
                               "Payment issue", "Access issue", "Joy buyer", "Other"]
    private var order: DeliveryOrder? {
        store.active?.orders.first(where: { $0.id == orderID })
    }

    var body: some View {
        ScrollView {
            if let order {
                VStack(alignment: .leading, spacing: 14) {
                    SGHeading(title: "Customer delivery", subtitle: "Postal \(order.postal)")
                    SGCard {
                        VStack(alignment: .leading, spacing: 12) {
                            HStack {
                                Text("Customer details").font(.headline)
                                    .foregroundStyle(SGStyle.gold)
                                Spacer()
                                SGStatus(status: order.status)
                            }
                            SGField(title: "Name", value: order.name)
                            SGField(title: "Full address", value: order.address)
                            SGField(title: "WhatsApp phone", value: order.phone)
                            SGField(title: "Amount SGD", value: order.amount.isEmpty ? "—" : "$\(order.amount)")
                            SGField(title: "Payment method", value: order.payment)
                            if let eta = order.arrival { SGField(title: "ETA", value: SGFormat.date(eta)) }
                            if let delivered = order.deliveredAt { SGField(title: "Delivered", value: SGFormat.date(delivered)) }
                            if let reason = order.holdReason { SGField(title: "Hold reason", value: reason) }
                        }
                    }
                    SGCard {
                        VStack(spacing: 10) {
                            SGAction(title: "Navigate", filled: false) { showNavigation = true }
                            if !order.phone.isEmpty, let url = whatsappURL(order) {
                                Link(destination: url) {
                                    Text("Send WhatsApp Draft")
                                        .foregroundStyle(SGStyle.navy)
                                        .font(.system(size: 15, weight: .semibold))
                                        .frame(maxWidth: .infinity, minHeight: 53)
                                        .background(SGStyle.blue, in: RoundedRectangle(cornerRadius: 18))
                                }
                            }
                            Text("WhatsApp messages open as drafts only. Nothing is sent automatically.")
                                .font(.caption2).foregroundStyle(SGStyle.muted)
                        }
                    }
                    SGCard {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Proof Photos").font(.headline).foregroundStyle(SGStyle.gold)
                            PhotosPicker(selection: $deliveryPhoto, matching: .images) {
                                Label("Add Proof of Delivery", systemImage: "camera")
                                    .foregroundStyle(SGStyle.blue)
                            }
                            PhotosPicker(selection: $paymentPhoto, matching: .images) {
                                Label("Add Proof of Payment", systemImage: "creditcard")
                                    .foregroundStyle(SGStyle.blue)
                            }
                            if let data = order.proofDelivery.flatMap(Data.init(base64Encoded:)),
                               let image = UIImage(data: data) {
                                Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 160)
                                    .clipShape(RoundedRectangle(cornerRadius: 12))
                            }
                            if let data = order.proofPayment.flatMap(Data.init(base64Encoded:)),
                               let image = UIImage(data: data) {
                                Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 160)
                                    .clipShape(RoundedRectangle(cornerRadius: 12))
                            }
                        }
                    }
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
                }.padding(20)
            }
        }
        .background(SGStyle.navy)
        .navigationTitle(order?.postal ?? "Delivery")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("Choose Navigation", isPresented: $showNavigation,
                            titleVisibility: .visible) {
            if let order {
                Button("Apple Maps") { openNavigation(order, provider: "Apple Maps") }
                Button("Google Maps") { openNavigation(order, provider: "Google Maps") }
                Button("Cancel", role: .cancel) {}
            }
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

    private func whatsappURL(_ order: DeliveryOrder) -> URL? {
        let digits = order.phone.filter(\.isNumber)
        let text = order.originalMessage.isEmpty
            ? "Hello, this is SG Delivery regarding parcel delivery to \(order.postal)."
            : order.originalMessage
        var components = URLComponents(string: "https://wa.me/\(digits)")
        components?.queryItems = [URLQueryItem(name: "text", value: text)]
        return components?.url
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
            let maxDimension = max(image.size.width, image.size.height)
            let factor = min(1, 1200 / max(1, maxDimension))
            let size = CGSize(width: image.size.width * factor, height: image.size.height * factor)
            let renderer = UIGraphicsImageRenderer(size: size)
            let small = renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
            guard let output = small.jpegData(compressionQuality: 0.42) else {
                store.errorMessage = "Could not compress proof photo"; return
            }
            store.saveProof(orderID: orderID, data: output, payment: payment)
        } catch { store.errorMessage = "Could not load proof photo: \(error.localizedDescription)" }
    }
}

struct HistoryView: View {
    @EnvironmentObject private var store: DeliveryStore
    @State private var restoring = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                SGHeading(title: "History", subtitle: "Review past delivery routes and backups.")
                SGCard {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Export & Backup").font(.headline).foregroundStyle(SGStyle.gold)
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
                        SGAction(title: "Restore iOS Backup", filled: false) {
                            restoring = true
                        }
                        Text("Backups contain private customer addresses and photos. JSON is unencrypted and is not Android ZIP-compatible.")
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
        .fileImporter(isPresented: $restoring, allowedContentTypes: [.json]) { selection in
            do {
                let url = try selection.get()
                guard url.startAccessingSecurityScopedResource() else {
                    throw DeliveryError.invalid("Cannot access selected backup")
                }
                defer { url.stopAccessingSecurityScopedResource() }
                try store.restoreJSON(Data(contentsOf: url))
                store.noticeMessage = "iOS backup restored."
            } catch { store.errorMessage = "Restore failed: \(error.localizedDescription)" }
        }
    }
}
