import SwiftUI

@main struct IZZDeliveryApp: App {
    @StateObject private var store = AppStore()
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store).tint(Brand.gold).preferredColorScheme(.dark)
                .environment(\.locale, Locale(identifier: "en_SG"))
                .environment(\.timeZone, TimeZone(identifier: "Asia/Singapore")!)
        }
    }
}
struct RootView: View {
    @EnvironmentObject var store: AppStore
    var body: some View {
        TabView(selection: $store.tab) {
            NavigationStack { HomeView() }.tabItem { Label("Home", systemImage: "house") }.tag(0)
            NavigationStack { RouteView() }.tabItem { Label("Route", systemImage: "point.topleft.down.to.point.bottomright.curvepath") }.tag(1)
            NavigationStack { HistoryView() }.tabItem { Label("History", systemImage: "clock.arrow.circlepath") }.tag(2)
            NavigationStack { SettingsView() }.tabItem { Label("Settings", systemImage: "gearshape") }.tag(3)
        }
        .alert("IZZ Delivery", isPresented: Binding(get: { store.error != nil }, set: { if !$0 { store.error = nil } })) {
            Button("OK") { store.error = nil }
        } message: { Text(store.error ?? "") }
        .alert("IZZ Delivery", isPresented: Binding(get: { store.notice != nil }, set: { if !$0 { store.notice = nil } })) {
            Button("OK") { store.notice = nil }
        } message: { Text(store.notice ?? "") }
    }
}
