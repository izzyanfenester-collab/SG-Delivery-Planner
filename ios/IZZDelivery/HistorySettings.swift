import SwiftUI

struct HistoryView: View {
    @EnvironmentObject var store: AppStore
    var body: some View {
        List {
            if store.state.routes.isEmpty { Text("Saved routes will appear here.").foregroundStyle(.secondary) }
            ForEach(store.state.routes) { route in
                Button { store.select(route) } label: {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(SingaporeTime.text(route.start)).font(.headline).foregroundStyle(Brand.gold)
                        Text(route.startLocation.label)
                        if let s = try? SharedBridge.summary(route) {
                            Text("Finish: \(SingaporeTime.text(s.finish))")
                            Text("\(s.totalParcel) parcels · \(s.delivered) Delivered · \(s.onHold) On Hold")
                            Text("\(s.skipped) Skipped · \(s.pending) Pending · \(String(format: "%.2f", s.totalKm)) KM")
                            Text("Cash on Hand: SGD \(s.cashOnHand.amount) · Tax: SGD \(s.tax.amount)")
                        }
                    }.font(.subheadline).foregroundStyle(.white).padding(.vertical, 8)
                }.listRowBackground(Brand.card)
            }
        }.screenBackground().navigationTitle("History")
    }
}
struct SettingsView: View {
    @EnvironmentObject var store: AppStore
    @State private var settings = Settings()
    var body: some View {
        Form {
            Section("Planning") {
                Stepper("Delivery service: \(settings.serviceMinutes) min", value: $settings.serviceMinutes, in: 0...120)
                Picker("Traffic estimate", selection: $settings.mode) { ForEach(["Light Traffic", "Normal Traffic", "Heavy Traffic"], id: \.self) { Text($0) } }
                Text("Traffic settings are planning assumptions, not live traffic predictions.").font(.caption)
            }
            Section("Routing server") {
                TextField("HTTPS OSRM endpoint", text: $settings.endpoint).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                Text("The default server is an evaluation service. For operational use, configure an OSRM-compatible provider with Singapore road data and support for 51-location matrices. Do not put credentials in the URL.").font(.caption)
            }
            Button("Save Settings") {
                guard RouteService.validEndpoint(settings.endpoint) else { store.error = "Enter an HTTPS routing server address without credentials, a query or a fragment."; return }
                if store.update({ $0.settings = settings }) { store.notice = "Settings saved." }
            }
            Section("IZZ Delivery") {
                Text("Navy & Gold · Native iOS")
                Text("Routes, delivery progress and manual amounts are saved in protected local storage and excluded from backup. New planning requires internet access. Addresses are sent to OneMap and coordinates to your routing provider. Navigate opens Apple Maps.").font(.caption)
                ChatGPTButton()
            }
        }.screenBackground().navigationTitle("Settings").onAppear { settings = store.state.settings }
    }
}
