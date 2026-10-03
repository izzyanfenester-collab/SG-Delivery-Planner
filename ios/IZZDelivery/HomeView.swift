import SwiftUI

struct HomeView: View {
    @EnvironmentObject var store: AppStore
    @State private var picker = false
    @State private var replaceHome = false
    private var postal: Binding<String> { Binding(get: { store.state.postalText }, set: { value in store.update { $0.postalText = value } }) }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                HStack(spacing: 14) {
                    Image("BrandIcon").resizable().scaledToFit().frame(width: 72, height: 72).clipShape(RoundedRectangle(cornerRadius: 18))
                    VStack(alignment: .leading) { Text("IZZ Delivery").font(.largeTitle.bold()); Text("Plan with precision. Deliver with confidence.").font(.subheadline).foregroundStyle(.secondary) }
                }
                VStack(alignment: .leading, spacing: 14) {
                    Label("Start & End", systemImage: "flag.checkered").font(.headline).foregroundStyle(Brand.gold)
                    Text(store.state.selectedLocation.label).font(.title3.bold())
                    Text(store.state.selectedLocation.address).foregroundStyle(.secondary)
                    Text("Your route returns to this location.").font(.caption).foregroundStyle(.secondary)
                    Button("Woodlands Checkpoint") { store.update { $0.selectedLocation = StartLocation() } }.buttonStyle(.bordered)
                    Button { picker = true } label: { Label("Choose on Map", systemImage: "map") }.buttonStyle(.bordered)
                    HStack {
                        Button(store.state.home == nil ? "Set Home" : "Update Home") {
                            if store.state.home == nil { saveHome() } else { replaceHome = true }
                        }.buttonStyle(.bordered)
                        Button("Use Home") { if let home = store.state.home { store.update { $0.selectedLocation = home } } }.buttonStyle(.bordered).disabled(store.state.home == nil)
                    }
                }.premiumCard()
                VStack(alignment: .leading, spacing: 12) {
                    Text("Postal codes").font(.headline).foregroundStyle(Brand.gold)
                    Text("Enter 1–50 six-digit Singapore postal codes. Separate with spaces, commas or new lines.").font(.subheadline).foregroundStyle(.secondary)
                    TextEditor(text: postal).frame(minHeight: 145).padding(8).background(Brand.navy, in: RoundedRectangle(cornerRadius: 12)).accessibilityLabel("Postal codes")
                    DatePicker("Departure (Singapore)", selection: Binding(get: { store.state.departure }, set: { date in store.update { $0.departure = date } }))
                }.premiumCard()
                if let progress = store.progress {
                    HStack { ProgressView(); Text(progress) }.premiumCard()
                    Button("Cancel Planning", role: .cancel) { store.cancelPlanning() }
                } else {
                    Button { store.optimize() } label: { Label("Optimize Route", systemImage: "sparkles") }.buttonStyle(GoldButton())
                }
                Button { store.open(URL(string: "https://m.customs.gov.sg/CustomsTravellerPortal/")!) } label: { Label("Tax Declare", systemImage: "doc.text") }.buttonStyle(GoldButton())
                ChatGPTButton()
            }.padding(20)
        }.screenBackground().navigationTitle("Home").navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $picker) { NavigationStack { LocationPickerView(initial: store.state.selectedLocation) { location in store.update { $0.selectedLocation = location } } }.environmentObject(store) }
            .confirmationDialog("Replace your saved Home with this location?", isPresented: $replaceHome, titleVisibility: .visible) { Button("Update Home") { saveHome() }; Button("Cancel", role: .cancel) {} }
    }
    private func saveHome() {
        var home = store.state.selectedLocation; home.type = "HOME"
        if store.update({ $0.home = home; $0.selectedLocation = home }) { store.notice = "Home saved on this device." }
    }
}
