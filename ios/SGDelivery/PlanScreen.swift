import SwiftUI

struct PlanView: View {
    @EnvironmentObject private var store: DeliveryStore
    let onPlanned: () -> Void
    @State private var importing = false
    @State private var reviewingPostals = false

    private var invalidManual: [String] {
        if !store.draftOrders.isEmpty { return [] }
        return store.postalInput.split(whereSeparator: { $0.isWhitespace || $0 == "," || $0 == ";" })
            .map(String.init)
            .filter { $0.range(of: #"^[0-9]{6}$"#, options: .regularExpression) == nil }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                SGHeading(title: "Plan your delivery",
                          subtitle: "Plan a round trip from your selected Start & End location.")
                SGCard {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Start & End location").font(.headline).foregroundStyle(SGStyle.gold)
                        TextField("Singapore depot postal", text: $store.depotPostal)
                            .keyboardType(.numberPad)
                            .textFieldStyle(.roundedBorder)
                        Text(store.depotPostal == "738203"
                             ? "Woodlands Checkpoint • Singapore 738203"
                             : "Custom depot • Return to the selected postal")
                            .font(.caption).foregroundStyle(SGStyle.muted)
                    }
                }
                HStack(spacing: 10) {
                    SGCard {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Delivery date").font(.caption).foregroundStyle(SGStyle.muted)
                            DatePicker("Date", selection: $store.deliveryDate, displayedComponents: .date)
                                .datePickerStyle(.compact).labelsHidden().tint(SGStyle.gold)
                        }
                    }
                    SGCard {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Start time").font(.caption).foregroundStyle(SGStyle.muted)
                            DatePicker("Time", selection: $store.deliveryTime, displayedComponents: .hourAndMinute)
                                .datePickerStyle(.compact).labelsHidden().tint(SGStyle.gold)
                        }
                    }
                }
                HStack {
                    Text("Delivery time per stop: \(store.minutesPerStop) minutes")
                        .font(.subheadline).foregroundStyle(SGStyle.muted)
                    Spacer()
                    Stepper("", value: $store.minutesPerStop, in: 1...120).labelsHidden()
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("Singapore postal codes")
                        .font(.subheadline).foregroundStyle(SGStyle.muted)
                    TextEditor(text: Binding(
                        get: { store.postalInput },
                        set: { store.setPostalText($0) }
                    ))
                    .font(.system(size: 16, design: .monospaced))
                    .scrollContentBackground(.hidden)
                    .foregroundStyle(SGStyle.text)
                    .frame(minHeight: 205)
                    .padding(8)
                    .background(SGStyle.navy, in: RoundedRectangle(cornerRadius: 24))
                    .overlay(RoundedRectangle(cornerRadius: 24)
                        .stroke(SGStyle.outline, lineWidth: 1.3))
                }
                SGAction(title: "Import WhatsApp Orders", filled: false) { importing = true }
                if !store.draftOrders.isEmpty {
                    Text("Orders found: \(store.draft.count) · Duplicate postals preserved: \(store.duplicateParcelCount)")
                        .font(.subheadline).foregroundStyle(SGStyle.text)
                }
                HStack(spacing: 12) {
                    SGMetric(value: "\(store.draft.count)", title: "Valid stops")
                    SGMetric(value: "\(store.duplicateParcelCount)", title: "Duplicate postals preserved")
                }
                if !invalidManual.isEmpty {
                    SGCard(color: SGStyle.error) {
                        Text("Invalid postal codes: \(invalidManual.joined(separator: ", "))")
                            .font(.caption).foregroundStyle(.white)
                    }
                }
                HStack(spacing: 10) {
                    SGAction(title: store.isPlanning ? "Optimizing…" : "Optimize route",
                             enabled: !store.isPlanning && invalidManual.isEmpty && !store.draft.isEmpty) {
                        Task {
                            if await store.plan() { onPlanned() }
                        }
                    }
                    SGAction(title: "Clear postal code", filled: false, enabled: !store.isPlanning) {
                        store.clearDraft()
                    }
                }
                Link(destination: URL(string: "https://m.customs.gov.sg/CustomsTravellerPortal/")!) {
                    HStack {
                        Image(systemName: "doc.text")
                        Text("Tax Declare").fontWeight(.semibold)
                    }
                    .foregroundStyle(SGStyle.gold)
                    .frame(maxWidth: .infinity, minHeight: 58)
                    .background(SGStyle.navy, in: RoundedRectangle(cornerRadius: 20))
                    .overlay(RoundedRectangle(cornerRadius: 20).stroke(SGStyle.gold.opacity(0.7)))
                }
                Text("Routes follow roads and support up to 50 delivery parcels. ETA values are planning estimates without live traffic.")
                    .font(.caption).foregroundStyle(SGStyle.muted)
            }
            .padding(.horizontal, 20).padding(.vertical, 12)
        }
        .background(SGStyle.navy)
        .sheet(isPresented: $importing) {
            WhatsAppImportSheet()
                .environmentObject(store)
                .preferredColorScheme(.dark)
        }
        .sheet(isPresented: $store.showFailedPostalReview) {
            FailedRoutePostalSheet(onPlanned: onPlanned)
                .environmentObject(store)
                .preferredColorScheme(.dark)
        }
    }
}

struct WhatsAppImportSheet: View {
    @EnvironmentObject private var store: DeliveryStore
    @Environment(\.dismiss) private var dismiss
    @State private var parsed: ImportResult?
    @State private var selected = Set<UUID>()
    @State private var mode: String = "Orders"
    @State private var editingFailedIndex: Int?
    @State private var editText = ""
    @State private var editError: String?

    private var capacity: Int { max(0, 50 - store.draft.count) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 13) {
                    if let index = editingFailedIndex, let result = parsed,
                       result.failedOrders.indices.contains(index) {
                        SGHeading(title: "Edit failed order")
                        Text("Correct this one customer's original WhatsApp text, then recheck.")
                            .font(.caption).foregroundStyle(SGStyle.muted)
                        TextEditor(text: $editText)
                            .font(.system(size: 14, design: .monospaced))
                            .frame(height: 265)
                            .scrollContentBackground(.hidden)
                            .padding(8)
                            .background(SGStyle.card, in: RoundedRectangle(cornerRadius: 14))
                        if let editError {
                            Text(editError).font(.caption).foregroundStyle(.red)
                        }
                        SGAction(title: "RECHECK & ADD") { recheck(index) }
                        SGAction(title: "BACK", filled: false) { editingFailedIndex = nil; editError = nil }
                    } else if let result = parsed {
                        SGHeading(title: "WhatsApp Import")
                        Text("Orders found: \(result.orders.count)\nUnique postal codes: \(Set(result.orders.map(\.postal)).count)\nDuplicate parcels preserved: \(result.duplicateParcelCount)\nFailed: \(result.failed)")
                            .foregroundStyle(SGStyle.muted).font(.subheadline)
                        if result.failed > 0 {
                            Text("⚠ \(result.failed) order(s) need correction. No other parcels will be removed.")
                                .font(.caption).foregroundStyle(.orange)
                            SGAction(title: mode == "Failed" ? "BACK TO ORDERS" : "VIEW FAILED ORDERS (\(result.failed))",
                                     filled: false) { mode = mode == "Failed" ? "Orders" : "Failed" }
                        }
                        if !result.duplicatePostalGroups.isEmpty {
                            Text("⚠ \(result.duplicatePostalGroups.count) postal(s) are shared by multiple customers.")
                                .font(.caption).foregroundStyle(SGStyle.gold)
                            SGAction(title: mode == "Duplicates" ? "BACK TO ORDERS"
                                     : "VIEW DUPLICATE POSTALS (\(result.duplicatePostalGroups.count))",
                                     filled: false) { mode = mode == "Duplicates" ? "Orders" : "Duplicates" }
                        }
                        if mode == "Failed" {
                            ForEach(Array(result.failedOrders.enumerated()), id: \.offset) { index, failure in
                                SGCard(color: SGStyle.cardAlt) {
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(failure.customerName).bold()
                                        Text("Reason: \(failure.reason)").font(.caption).foregroundStyle(.orange)
                                        Text("Address: \(failure.address)").font(.caption)
                                        Text(failure.originalMessage).font(.caption2)
                                            .foregroundStyle(SGStyle.muted)
                                            .textSelection(.enabled)
                                        SGAction(title: "Edit text", filled: false) {
                                            editingFailedIndex = index
                                            editText = failure.originalMessage
                                            editError = nil
                                        }
                                    }
                                }
                            }
                        } else if mode == "Duplicates" {
                            ForEach(result.duplicatePostalGroups, id: \.postal) { group in
                                SGCard(color: SGStyle.cardAlt) {
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text("⚠ \(group.postal) — \(group.orders.count) customers")
                                            .foregroundStyle(SGStyle.gold).bold()
                                        ForEach(Array(group.orders.enumerated()), id: \.element.id) { i,order in
                                            Text("\(i+1). \(order.name.isEmpty ? "Unknown" : order.name) · $\(order.amount)")
                                                .font(.caption).foregroundStyle(SGStyle.text)
                                        }
                                    }
                                }
                            }
                        } else {
                            HStack {
                                Button("SELECT ALL") { selected = Set(result.orders.map(\.id)) }
                                    .foregroundStyle(SGStyle.blue)
                                Spacer()
                                Text("\(capacity) spaces available")
                                    .font(.caption).foregroundStyle(SGStyle.muted)
                            }
                            if selected.count > capacity {
                                Text("Select fewer orders (maximum 50 parcels).")
                                    .foregroundStyle(.red).font(.caption)
                            }
                            let counts = Dictionary(grouping: result.orders, by: \.postal).mapValues(\.count)
                            VStack(spacing: 0) {
                                ForEach(result.orders) { order in
                                    Button {
                                        if selected.contains(order.id) { selected.remove(order.id) }
                                        else { selected.insert(order.id) }
                                    } label: {
                                        HStack(spacing: 8) {
                                            Image(systemName: selected.contains(order.id)
                                                  ? "checkmark.square.fill" : "square")
                                                .foregroundStyle(SGStyle.blue)
                                                .font(.title3)
                                            Text("\(order.postal) · \(order.name.isEmpty ? "—" : order.name) · $\(order.amount)")
                                                .font(.system(size: 12, design: .rounded))
                                                .foregroundStyle(SGStyle.text)
                                                .lineLimit(1)
                                            Spacer(minLength: 2)
                                            if let count = counts[order.postal], count > 1 {
                                                Text("DUP ×\(count)")
                                                    .font(.system(size: 10, weight: .bold))
                                                    .foregroundStyle(SGStyle.gold)
                                            }
                                        }.frame(height: 43)
                                        .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                    Divider().overlay(SGStyle.outline)
                                }
                            }
                        }
                        if mode == "Orders" {
                            SGAction(title: "Use These Orders",
                                     enabled: !selected.isEmpty && selected.count <= capacity) {
                                store.addImportedOrders(result.orders.filter { selected.contains($0.id) })
                                dismiss()
                            }
                        }
                    } else {
                        SGHeading(title: "WhatsApp Import")
                        Text("Paste customer orders from WhatsApp. All customers at the same postal remain separate parcels.")
                            .font(.caption).foregroundStyle(SGStyle.muted)
                        TextEditor(text: $store.importText)
                            .font(.system(size: 13, design: .monospaced))
                            .scrollContentBackground(.hidden)
                            .frame(height: 280)
                            .padding(8)
                            .background(SGStyle.card, in: RoundedRectangle(cornerRadius: 16))
                        SGAction(title: "Paste from WhatsApp", filled: false) {
                            if let value = UIPasteboard.general.string {
                                store.importText = value
                            }
                        }
                        SGAction(title: "PREVIEW", enabled: !store.importText.isEmpty) {
                            let result = store.importPreview()
                            parsed = result
                            selected = Set(result.orders.map(\.id))
                            mode = "Orders"
                        }
                    }
                }.padding(20)
            }
            .background(SGStyle.navy)
            .navigationTitle("WhatsApp Import")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Cancel") { dismiss() }.foregroundStyle(SGStyle.gold)
                }
            }
        }
        .presentationDetents([.large])
    }

    private func recheck(_ index: Int) {
        guard var current = parsed else { return }
        let retry = WhatsAppParser.parse(editText)
        if retry.failed == 0 && retry.orders.count == 1 {
            let order = retry.orders[0]
            current.orders.append(order)
            current.failedOrders.remove(at: index)
            current.failed = current.failedOrders.count
            parsed = current
            selected.insert(order.id)
            editingFailedIndex = nil
            mode = current.failed > 0 ? "Failed" : "Orders"
        } else if retry.failed == 1 && retry.failedOrders.count == 1 {
            current.failedOrders[index] = retry.failedOrders[0]
            parsed = current
            editError = retry.failedOrders[0].reason
        } else {
            editError = "Keep exactly one complete customer order including its address and postal code."
        }
    }
}

struct FailedRoutePostalSheet: View {
    @EnvironmentObject private var store: DeliveryStore
    @Environment(\.dismiss) private var dismiss
    let onPlanned: () -> Void
    @State private var editingID: UUID?
    @State private var editText = ""
    @State private var editError: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    SGHeading(title: editingID == nil ? "Review Failed Orders" : "Edit Order")
                    if let id = editingID, let order = store.draft.first(where: { $0.id == id }) {
                        Text("Correct \(order.name.isEmpty ? "postal" : order.name)'s address or postal. Every other parcel remains unchanged.")
                            .font(.caption).foregroundStyle(SGStyle.muted)
                        TextEditor(text: $editText)
                            .scrollContentBackground(.hidden)
                            .font(.system(size: 14, design: .monospaced))
                            .frame(height: order.originalMessage.isEmpty ? 80 : 275)
                            .padding(8).background(SGStyle.card, in: RoundedRectangle(cornerRadius: 14))
                        if let editError { Text(editError).foregroundStyle(.red).font(.caption) }
                        SGAction(title: "SAVE CORRECTION") {
                            let error = store.correctDraftOrder(orderID: id, editedText: editText)
                            if let error { editError = error }
                            else { editingID = nil; editError = nil }
                        }
                        SGAction(title: "BACK", filled: false) { editingID = nil }
                    } else {
                        Text("These postals could not be verified by OneMap. A server outage or rate limit can also cause failures; do not guess a replacement postal.")
                            .foregroundStyle(SGStyle.muted).font(.caption)
                        if store.failedRoutePostals.isEmpty {
                            Text("All reported postal values were changed. Recheck before planning.")
                                .foregroundStyle(SGStyle.gold)
                        }
                        ForEach(store.failedRoutePostals) { failure in
                            SGCard(color: SGStyle.cardAlt) {
                                VStack(alignment: .leading, spacing: 7) {
                                    Text("Postal: \(failure.postal)")
                                        .font(.headline).foregroundStyle(SGStyle.gold)
                                    Text(failure.reason).font(.caption).foregroundStyle(.orange)
                                    let related = store.draft.filter { $0.postal == failure.postal }
                                    Text("\(related.count) affected order(s)")
                                        .foregroundStyle(SGStyle.muted).font(.caption)
                                    ForEach(related) { order in
                                        Divider().overlay(SGStyle.outline)
                                        SGField(title: "Customer", value: order.name.isEmpty ? "Manual entry" : order.name)
                                        SGField(title: "Address", value: order.address)
                                        SGAction(title: order.originalMessage.isEmpty ? "Edit postal code" : "Edit text",
                                                 filled: false) {
                                            editingID = order.id
                                            editText = order.originalMessage.isEmpty ? order.postal : order.originalMessage
                                            editError = nil
                                        }
                                    }
                                }
                            }
                        }
                        SGAction(title: store.isPlanning ? "Optimizing…" : "RETRY OPTIMIZE",
                                 enabled: !store.isPlanning) {
                            Task {
                                if await store.plan() {
                                    dismiss()
                                    onPlanned()
                                }
                            }
                        }
                    }
                }.padding(20)
            }
            .background(SGStyle.navy)
            .navigationTitle("Route Postal Review")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }.foregroundStyle(SGStyle.gold)
                }
            }
        }
        .presentationDetents([.large])
    }
}
