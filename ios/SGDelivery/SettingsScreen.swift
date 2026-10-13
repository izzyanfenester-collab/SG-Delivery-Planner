import SwiftUI

struct SGSettingsView: View {
    @EnvironmentObject private var store: DeliveryStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                SGHeading(title: "Settings", subtitle: "Set the defaults for your next route.")
                SGCard {
                    VStack(alignment: .leading, spacing: 13) {
                        Text("Default Navigation").font(.headline).foregroundStyle(SGStyle.gold)
                        Picker("Navigation", selection: $store.defaultNavigation) {
                            ForEach(["Ask every time", "Apple Maps", "Google Maps"], id: \.self) {
                                Text($0)
                            }
                        }.pickerStyle(.menu)
                        Divider()
                        Text("Default WhatsApp App").font(.headline).foregroundStyle(SGStyle.gold)
                        Text("WhatsApp draft opens using the phone's installed WhatsApp app (or its web fallback). Messages are never sent automatically.")
                            .font(.caption).foregroundStyle(SGStyle.muted)
                    }
                }
                SGCard {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Schedule").font(.headline).foregroundStyle(SGStyle.gold)
                        DatePicker("Delivery date", selection: $store.deliveryDate,
                                   displayedComponents: .date)
                        DatePicker("Start time (Singapore)", selection: $store.deliveryTime,
                                   displayedComponents: .hourAndMinute)
                        Stepper("Minutes per stop: \(store.minutesPerStop)",
                                value: $store.minutesPerStop, in: 1...120)
                        Text("Traffic estimate").font(.headline)
                        Picker("Traffic", selection: $store.trafficMode) {
                            Text("Light traffic").tag("Light Traffic")
                            Text("Normal traffic").tag("Normal")
                            Text("Heavy traffic").tag("Heavy Traffic")
                        }.pickerStyle(.segmented)
                    }
                }
                SGCard {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Appearance").font(.headline).foregroundStyle(SGStyle.gold)
                        Picker("Appearance", selection: $store.appearance) {
                            Text("Dark").tag("Dark")
                            Text("Light").tag("Light")
                            Text("System").tag("System")
                        }.pickerStyle(.segmented)
                        Text("Distance unit: kilometres (km)").font(.caption)
                            .foregroundStyle(SGStyle.muted)
                    }
                }
                SGCard {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Road routing").font(.headline).foregroundStyle(SGStyle.gold)
                        TextField("Routing server (HTTPS)", text: $store.roadServer)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .textFieldStyle(.roundedBorder)
                        Text("The public OSRM demo server may be unavailable or limit large delivery routes. For regular deliveries, use a reliable OSRM-compatible endpoint with Singapore map data.")
                            .font(.caption).foregroundStyle(SGStyle.muted)
                    }
                }
                Text("SG Delivery Native iOS · Version 1.4.1 parity port (in development)")
                    .font(.caption).foregroundStyle(SGStyle.muted)
            }.padding(20)
        }
        .background(SGStyle.navy)
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Done") { dismiss() }.foregroundStyle(SGStyle.gold)
            }
        }
    }
}
