import SwiftUI

struct RouteView: View {
    @EnvironmentObject var store: AppStore
    var body: some View {
        Group {
            if let route = store.route {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        Text("Your delivery route").font(.largeTitle.bold())
                        if let summary = try? SharedBridge.summary(route) {
                            VStack(alignment: .leading, spacing: 8) {
                                Text("\(summary.totalParcel) parcels · \(String(format: "%.2f", summary.totalKm)) KM").font(.title2.bold())
                                Text("Start & End: \(route.startLocation.label)")
                                Text("Departure: \(SingaporeTime.text(route.start))")
                                Text("Final delivery: \(SingaporeTime.text(route.stops.last?.leave ?? route.start))")
                                Text("Planned return: \(SingaporeTime.text(route.returned))")
                                Text("Planning estimates include traffic allowance and \(route.serviceMinutes) service minutes per delivery.").font(.caption).foregroundStyle(.secondary)
                            }.premiumCard()
                        }
                        NavigationLink { DeliveryModeView() } label: { Label("Delivery Mode", systemImage: "shippingbox") }.buttonStyle(GoldButton())
                        NavigationLink { RouteMapView(route: route) } label: { Label("Route Map", systemImage: "map") }.buttonStyle(.bordered)
                        NavigationLink { SummaryView().id(route.id) } label: { Label("Delivery Summary Report Preview", systemImage: "doc.text.magnifyingglass") }.buttonStyle(.bordered)
                        Text("Complete Delivery Schedule").font(.title2.bold()).foregroundStyle(Brand.gold)
                        ScheduleView(route: route)
                        ExportActions(route: route)
                        ChatGPTButton()
                    }.padding(20)
                }
            } else { ContentUnavailableView("Plan your first route", systemImage: "shippingbox", description: Text("Enter postal codes on Home and tap Optimize Route.")) }
        }.screenBackground().navigationTitle("Route").navigationBarTitleDisplayMode(.inline)
    }
}
struct ScheduleView: View {
    let route: Route
    private let headings = ["Stop", "Postal Code", "Block", "Area", "Arrival", "Distance From Previous", "Base Drive", "Traffic Buffer", "Planned Travel", "Delivery", "Leave", "Cumulative KM"]
    var body: some View {
        ScrollView(.horizontal) {
            VStack(alignment: .leading, spacing: 0) {
                row(headings, heading: true)
                row(["START", route.startLocation.postal, route.startLocation.label, route.startLocation.address, SingaporeTime.text(route.start), "0.00 KM", "0 min", "0 min", "0 min", "—", SingaporeTime.text(route.start), "0.00 KM"])
                ForEach(route.stops.indices, id: \.self) { i in
                    let stop = route.stops[i]
                    row(["\(i+1) · \(stop.statusLabel)", stop.place.postal, stop.place.block, stop.place.area, SingaporeTime.text(stop.arrival), km(stop.leg.km), minutes(stop.leg.baseSeconds), minutes(stop.leg.bufferSeconds), minutes(stop.leg.plannedSeconds), "\(route.serviceMinutes) min", SingaporeTime.text(stop.leave), km(stop.cumulativeKm)])
                }
                row(["END", route.startLocation.postal, route.startLocation.label, route.startLocation.address, SingaporeTime.text(route.returned), km(route.returnLeg.km), minutes(route.returnLeg.baseSeconds), minutes(route.returnLeg.bufferSeconds), minutes(route.returnLeg.plannedSeconds), "—", SingaporeTime.text(route.returned), km((route.stops.last?.cumulativeKm ?? 0)+route.returnLeg.km)])
            }
        }.background(Brand.card, in: RoundedRectangle(cornerRadius: 16))
    }
    private func row(_ cells: [String], heading: Bool = false) -> some View {
        HStack(alignment: .top, spacing: 0) { ForEach(cells.indices, id: \.self) { i in Text(cells[i]).font(heading ? .headline : .subheadline).foregroundStyle(heading ? Brand.gold : .white).frame(width: i == 4 || i == 10 ? 180 : 145, alignment: .leading).padding(12) } }.background(heading ? Brand.navy : Brand.card)
    }
    private func km(_ value: Double) -> String { String(format: "%.2f KM", value) }
    private func minutes(_ value: Double) -> String { String(format: "%.1f min", value/60) }
}
struct DeliveryModeView: View {
    @EnvironmentObject var store: AppStore
    @State private var hold = false
    @State private var reason = "No reason"
    @State private var note = ""
    var body: some View {
        Group {
            if let route = store.route, route.current < route.stops.count {
                let stop = route.stops[route.current]
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        Text("Stop \(route.current+1) of \(route.stops.count)").font(.title2).foregroundStyle(Brand.gold)
                        Text(stop.place.postal).font(.system(size: 48, weight: .bold, design: .rounded)).minimumScaleFactor(0.6)
                        VStack(alignment: .leading, spacing: 10) {
                            Text(stop.place.address).font(.title2.bold()); Text("\(stop.place.block) · \(stop.place.area)")
                            Text("Planned arrival: \(SingaporeTime.text(stop.arrival))")
                            Text("Delivery period: \(SingaporeTime.text(stop.arrival, date: false)) – \(SingaporeTime.text(stop.leave))")
                            Text(stop.statusLabel).font(.headline).foregroundStyle(Brand.status(stop.status))
                            if let reason = stop.holdReason { Text(reason+(stop.holdNote.map { ": "+$0 } ?? "")) }
                            if let completed = stop.completedAt { Text("Delivered: \(SingaporeTime.text(completed))") }
                        }.premiumCard()
                        Button { store.navigate(stop.place) } label: { Label("Navigate", systemImage: "location") }.buttonStyle(GoldButton())
                        Button { store.review("DELIVERED") } label: { Label("Delivered", systemImage: "checkmark.circle") }.buttonStyle(GoldButton())
                        Button { reason = "No reason"; note = ""; hold = true } label: { Label("On Hold", systemImage: "pause.circle") }.buttonStyle(.borderedProminent).tint(.orange)
                        HStack { Button("Skipped") { store.review("SKIPPED") }; Button("Pending") { store.review("PENDING") } }.buttonStyle(.bordered)
                        Button("Next Stop (keep status)") { store.review("NEXT") }.buttonStyle(.bordered)
                        NavigationLink { RouteMapView(route: route) } label: { Label("Route Map", systemImage: "map") }
                        ChatGPTButton()
                    }.padding(20)
                }
            } else if store.route != nil { SummaryView().id(store.route?.id) }
            else { ContentUnavailableView("No active route", systemImage: "shippingbox") }
        }.screenBackground().navigationTitle("Delivery Mode").navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $hold) {
                NavigationStack {
                    Form {
                        Picker("Reason (optional)", selection: $reason) { ForEach(["No reason", "Customer not home", "No answer", "Reschedule", "Payment issue", "Access issue", "Other"], id: \.self) { Text($0) } }
                        if reason == "Other" { TextField("Note (up to 160 characters)", text: $note).onChange(of: note) { _, value in note = String(value.prefix(160)) } }
                        Button("Put On Hold") { store.review("ON_HOLD", reason: reason == "No reason" ? nil : reason, note: note); hold = false }
                    }.navigationTitle("On Hold").toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { hold = false } } }
                }.presentationDetents([.medium, .large])
            }
    }
}
