import SwiftUI
import MapKit
import PhotosUI
import UniformTypeIdentifiers

@main
struct SGDeliveryApp: App {
    @StateObject private var store = DeliveryStore()
    var body: some Scene {
        WindowGroup {
            MainView()
                .environmentObject(store)
                .preferredColorScheme(.dark)
        }
    }
}

struct MainView: View {
    @EnvironmentObject private var store: DeliveryStore
    @State private var selectedTab = 0
    @State private var showingSettings = false

    private let tabs = ["Home", "Route", "Delivery", "Map", "History"]

    var body: some View {
        VStack(spacing: 0) {
            topBar
            if store.isPlanning {
                ProgressView().tint(SGStyle.gold).padding(6)
            }
            if let error = store.errorMessage, !error.isEmpty {
                SGCard(color: SGStyle.error) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(error).foregroundStyle(.white)
                            .font(.system(size: 13, weight: .medium, design: .serif))
                        if !store.failedRoutePostals.isEmpty {
                            Button("REVIEW FAILED ORDERS (\(store.failedRoutePostals.count))") {
                                store.showFailedPostalReview = true
                                selectedTab = 0
                            }
                            .foregroundStyle(SGStyle.blue).font(.subheadline.bold())
                        }
                        Button("Dismiss") { store.errorMessage = nil }
                            .font(.caption.bold()).foregroundStyle(SGStyle.gold)
                    }
                }
                .padding(.horizontal, 16).padding(.top, 8)
            }
            if let notice = store.noticeMessage, !notice.isEmpty {
                HStack {
                    Text(notice).font(.caption).foregroundStyle(SGStyle.muted)
                    Spacer()
                    Button {
                        store.noticeMessage = nil
                    } label: { Image(systemName: "xmark.circle") }
                        .foregroundStyle(SGStyle.muted)
                }
                .padding(10)
                .background(SGStyle.cardAlt, in: RoundedRectangle(cornerRadius: 10))
                .padding(.horizontal, 16).padding(.top, 4)
            }
            NavigationStack {
                Group {
                    switch selectedTab {
                    case 0: PlanView(onPlanned: { selectedTab = 1 })
                    case 1: RouteView()
                    case 2: DeliveryListView()
                    case 3: RouteMapView()
                    default: HistoryView()
                    }
                }
                .toolbar(.hidden, for: .navigationBar)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            tabBar
        }
        .background(SGStyle.navy.ignoresSafeArea())
        .sheet(isPresented: $showingSettings) {
            NavigationStack { SGSettingsView() }
                .environmentObject(store)
                .preferredColorScheme(.dark)
        }
    }

    private var topBar: some View {
        HStack(spacing: 12) {
            Text("SG DELIVERY")
                .font(.system(size: 14, weight: .bold, design: .rounded))
                .tracking(1.4)
                .foregroundStyle(SGStyle.gold)
            Spacer()
            Button { showingSettings = true } label: {
                Image(systemName: "gearshape.fill")
                    .font(.system(size: 27)).foregroundStyle(SGStyle.gold)
                    .frame(width: 46, height: 50)
            }
            Link(destination: URL(string: "https://chatgpt.com/")!) {
                Image(systemName: "sparkle")
                    .font(.system(size: 26, weight: .medium))
                    .foregroundStyle(SGStyle.gold)
                    .frame(width: 44, height: 50)
            }
        }
        .padding(.horizontal, 18)
        .padding(.top, 5).padding(.bottom, 10)
        .background(SGStyle.navy)
    }

    private var tabBar: some View {
        HStack(spacing: 5) {
            ForEach(Array(tabs.enumerated()), id: \.offset) { index, title in
                Button {
                    if !store.isPlanning { selectedTab = index }
                } label: {
                    Text(title)
                        .font(.system(size: 13, weight: .semibold, design: .serif))
                        .foregroundStyle(index == selectedTab ? SGStyle.gold : SGStyle.muted)
                        .frame(maxWidth: .infinity, minHeight: 67)
                        .background(index == selectedTab ? SGStyle.royal : Color.clear,
                                    in: RoundedRectangle(cornerRadius: 21))
                }
                .disabled(store.isPlanning)
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 7).padding(.vertical, 8)
        .background(SGStyle.navy)
    }
}
