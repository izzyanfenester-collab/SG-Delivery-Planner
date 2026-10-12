import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject var store: DeliveryStore
    @State private var importText = ""
    @State private var message = ""
    @State private var showingImporter = false

    var body: some View {
        NavigationStack {
            List {
                Section("Paste Singapore postal codes or WhatsApp orders") {
                    TextEditor(text: $importText).frame(minHeight: 125)
                    Button("Create Delivery List") {
                        let count = store.create(from: importText)
                        message = count == 0 ? "No valid six-digit postal codes found." : "Added \(count) unique postal codes. Optimization is not yet available."
                    }
                    if !message.isEmpty { Text(message).font(.footnote) }
                }
                Section("Saved Routes") {
                    ForEach(store.routes) { route in
                        NavigationLink {
                            RouteView(routeID: route.id)
                        } label: {
                            VStack(alignment: .leading) {
                                Text(route.title).font(.headline)
                                Text("\(route.stops.count) stops • \(route.createdAt.formatted(date: .abbreviated, time: .shortened))")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onDelete { store.routes.remove(atOffsets: $0) }
                }
                Section("Backup") {
                    ShareLink(item: BackupDocument(data: store.exportData() ?? Data()),
                              preview: SharePreview("SGDeliveryBackup.json")) {
                        Text("Export Backup JSON")
                    }
                    Button("Restore Backup JSON") { showingImporter = true }
                }
            }
            .navigationTitle("SG Delivery")
            .fileImporter(isPresented: $showingImporter, allowedContentTypes: [.json]) { result in
                do {
                    let url = try result.get()
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    try store.restore(from: Data(contentsOf: url))
                    message = "Backup restored."
                } catch { message = "Restore failed: \(error.localizedDescription)" }
            }
        }
    }
}

struct BackupDocument: Transferable {
    let data: Data
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .json) { document in
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("SGDeliveryBackup.json")
            try document.data.write(to: url)
            return SentTransferredFile(url)
        }
    }
}

struct RouteView: View {
    @EnvironmentObject var store: DeliveryStore
    let routeID: UUID
    var route: DeliveryRoute? { store.routes.first(where: { $0.id == routeID }) }
    var body: some View {
        List {
            if let route {
                Section("Start / End") { Text(route.startLocation) }
                Section("Stops — input order (not optimized)") {
                    ForEach(Array(route.stops.enumerated()), id: \.element.id) { index, stop in
                        VStack(alignment: .leading, spacing: 7) {
                            HStack {
                                Text("\(index + 1). \(stop.postalCode)").font(.headline)
                                Spacer()
                                Text(stop.status.rawValue.capitalized).font(.caption)
                            }
                            if let date = stop.deliveredAt {
                                Text("Delivered \(date.formatted())").font(.caption)
                            }
                            HStack {
                                Button("Delivered") { store.setStatus(routeID: routeID, stopID: stop.id, status: .delivered) }
                                Button("Hold") { store.setStatus(routeID: routeID, stopID: stop.id, status: .hold) }
                                Button("Skip") { store.setStatus(routeID: routeID, stopID: stop.id, status: .skipped) }
                            }.buttonStyle(.bordered)
                            Link("Open in Google Maps", destination: URL(string: "https://www.google.com/maps/search/?api=1&query=Singapore%20\(stop.postalCode)")!)
                        }
                    }
                }
            }
        }.navigationTitle("Delivery Route")
    }
}
