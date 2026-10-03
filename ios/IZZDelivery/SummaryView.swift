import SwiftUI

struct SummaryView: View {
    @EnvironmentObject var store: AppStore
    @State private var cash = "0.00"
    @State private var tax = "0.00"
    @State private var saved = false
    @State private var sharing: SharePayload?
    var body: some View {
        ScrollView {
            if let route = store.route {
                VStack(alignment: .leading, spacing: 20) {
                    Text("IZZ Delivery Summary").font(.largeTitle.bold())
                    if let summary = try? SharedBridge.summary(route) {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("\(summary.delivered) / \(summary.totalParcel) delivered").font(.title.bold())
                            Text("Success rate: \(String(format: "%.1f", summary.successRate))%").foregroundStyle(Brand.gold)
                            Text("On Hold: \(summary.onHold) · Skipped: \(summary.skipped) · Pending: \(summary.pending)")
                            Text("Start & End: \(route.startLocation.label)")
                            Text("Start: \(SingaporeTime.text(summary.start))")
                            Text("Finish: \(SingaporeTime.text(summary.finish))")
                            Text("Route time: \(summary.totalRouteSeconds/60) min")
                            Text("Total: \(String(format: "%.2f", summary.totalKm)) KM")
                            Text("Planned return: \(SingaporeTime.text(summary.returned))")
                            if summary.pending+summary.onHold+summary.skipped > 0 { Text("Review complete does not mean all parcels were delivered.").font(.caption).foregroundStyle(.secondary) }
                        }.premiumCard()
                    }
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Manual SGD amounts").font(.headline).foregroundStyle(Brand.gold)
                        TextField("Cash on Hand", text: $cash).keyboardType(.decimalPad).textFieldStyle(.roundedBorder).accessibilityLabel("Cash on Hand in SGD")
                        TextField("Tax", text: $tax).keyboardType(.decimalPad).textFieldStyle(.roundedBorder).accessibilityLabel("Tax in SGD")
                        Text("Cash on Hand and Tax are independent amounts. Save edits before sharing or exporting.").font(.caption).foregroundStyle(.secondary)
                        Button("Save Summary") { if store.saveSummary(cash: cash, tax: tax) { cash = store.route?.cashOnHand.amount ?? cash; tax = store.route?.tax.amount ?? tax; saved = true } }.buttonStyle(GoldButton())
                    }.premiumCard()
                    ReportActions(route: route)
                    ExportActions(route: route)
                    Text("Revisit a delivery").font(.headline)
                    ForEach(route.stops.indices, id: \.self) { i in
                        NavigationLink { DeliveryModeView() } label: { HStack { Text("\(i+1). \(route.stops[i].place.postal)"); Spacer(); Text(route.stops[i].statusLabel).foregroundStyle(Brand.status(route.stops[i].status)) } }.simultaneousGesture(TapGesture().onEnded { store.revisit(i) })
                    }
                }.padding(20)
            }
        }.screenBackground().navigationTitle("Summary").navigationBarTitleDisplayMode(.inline)
            .onAppear { cash = store.route?.cashOnHand.amount ?? "0.00"; tax = store.route?.tax.amount ?? "0.00" }
            .sheet(isPresented: $saved) {
                NavigationStack {
                    ScrollView { VStack(alignment: .leading, spacing: 20) { Text("Summary saved").font(.title.bold()); if let route = store.route { Text((try? Report.text(route)) ?? ""); ReportActions(route: route) } }.padding(20) }
                        .navigationTitle("IZZ Delivery").toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { saved = false } } }
                }
            }
    }
}
enum Report {
    static func text(_ route: Route) throws -> String {
        let s = try SharedBridge.summary(route)
        return """
        IZZ Delivery Summary
        Date: \(SingaporeTime.day(route.start))
        Start Location: \(route.startLocation.label) — \(route.startLocation.address)
        End Location: \(route.startLocation.label) — \(route.startLocation.address)
        Start: \(SingaporeTime.text(s.start))
        Finish: \(SingaporeTime.text(s.finish))
        Planned Return: \(SingaporeTime.text(s.returned))
        Route Time: \(s.totalRouteSeconds/60) min
        Total Parcel: \(s.totalParcel)
        Delivered: \(s.delivered)
        On Hold: \(s.onHold)
        Skipped: \(s.skipped)
        Pending: \(s.pending)
        Success Rate: \(String(format: "%.1f", s.successRate))%
        Total KM: \(String(format: "%.2f", s.totalKm))
        Cash on Hand: SGD \(s.cashOnHand.amount)
        Tax: SGD \(s.tax.amount)
        """
    }
}
struct ReportActions: View {
    @EnvironmentObject var store: AppStore
    let route: Route
    @State private var sharing: SharePayload?
    var body: some View {
        HStack {
            Button { do { sharing = SharePayload(items: [try Report.text(route)]) } catch { store.error = store.userMessage(error) } } label: { Label("Share Report", systemImage: "square.and.arrow.up") }
            Button { do { UIPasteboard.general.string = try Report.text(route); store.notice = "Report copied to clipboard" } catch { store.error = store.userMessage(error) } } label: { Label("Copy Text", systemImage: "doc.on.doc") }
        }.buttonStyle(.bordered).sheet(item: $sharing) { ShareSheet(items: $0.items) }
    }
}
