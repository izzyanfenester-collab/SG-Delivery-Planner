import SwiftUI

struct FinanceReportView: View {
    @EnvironmentObject private var store: DeliveryStore
    @Environment(\.dismiss) private var dismiss
    @State private var cash = "0.00"
    @State private var tax = "0.00"
    @State private var rate = "3.60"
    @State private var remark = ""
    @State private var excelURL: URL?
    @State private var showShare = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                SGHeading(title: "Delivery Report Summary",
                          subtitle: "Android-style cash, tax, SGD→MYR rate and Excel.")
                if let route = store.active {
                    SGCard(stroke: SGStyle.gold) {
                        VStack(alignment: .leading, spacing: 10) {
                            ForEach(Array(DeliveryReportSummary(route: route).fields.enumerated()),
                                    id: \.offset) { _, item in
                                HStack(alignment: .top) {
                                    Text(item.0).font(.subheadline.bold()).foregroundStyle(SGStyle.gold)
                                    Spacer(minLength: 8)
                                    Text(item.1).font(.subheadline).foregroundStyle(SGStyle.text)
                                        .multilineTextAlignment(.trailing)
                                }
                            }
                        }
                    }
                    SGCard {
                        VStack(alignment: .leading, spacing: 11) {
                            Text("Edit & Save Finance").foregroundStyle(SGStyle.gold).font(.headline)
                            LabeledContent("Cash on Hand (SGD)") {
                                TextField("0.00", text: $cash)
                                    .keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                            }
                            LabeledContent("Tax (SGD)") {
                                TextField("0.00", text: $tax)
                                    .keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                            }
                            LabeledContent("SGD to MYR rate") {
                                TextField("3.60", text: $rate)
                                    .keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                            }
                            TextField("Remark", text: $remark, axis: .vertical)
                                .lineLimit(2...4).textFieldStyle(.roundedBorder)
                            if let amount = MoneyCalculator.amount(tax),
                               let exchange = MoneyCalculator.positiveRate(rate) {
                                let myrAmount = MoneyCalculator.taxMYR(tax: amount, rate: exchange)
                                Text("Tax converted to RM " + MoneyCalculator.display(myrAmount))
                                    .foregroundStyle(SGStyle.blue)
                            }
                            SGAction(title: "Save Finance") {
                                if let error = store.updateRouteFinance(cash: cash, tax: tax, rate: rate, remark: remark) {
                                    store.errorMessage = error
                                }
                            }
                        }
                    }
                    SGCard {
                        VStack(spacing: 10) {
                            SGAction(title: "Prepare Excel (.xlsx)") {
                                excelURL = store.exportExcel()
                            }
                            if let excelURL {
                                ShareLink(item: excelURL) {
                                    Label("Share / Save Excel XLSX", systemImage: "square.and.arrow.up")
                                        .frame(maxWidth: .infinity, minHeight: 48)
                                        .foregroundStyle(SGStyle.blue)
                                }
                            }
                            ShareLink(item: DeliveryReportSummary(route: route).printable) {
                                Label("Share Delivery Summary Text", systemImage: "doc.text")
                                    .foregroundStyle(SGStyle.blue)
                            }
                            Text("Excel has two worksheets: Delivery Summary and Parcels. Files include customer addresses and contact details; share privately.")
                                .font(.caption2).foregroundStyle(SGStyle.muted)
                        }
                    }
                } else {
                    SGCard { Text("Plan or select a route first.").foregroundStyle(SGStyle.muted) }
                }
            }
            .padding(20)
        }
        .background(SGStyle.navy)
        .onAppear { load() }
        .navigationTitle("Delivery Report")
        .toolbar { ToolbarItem(placement: .topBarTrailing) {
            Button("Done") { dismiss() }.foregroundStyle(SGStyle.gold)
        } }
    }

    private func load() {
        guard let route = store.active else { return }
        cash = route.cashOnHand ?? "0.00"
        tax = route.tax ?? "0.00"
        rate = route.exchangeRate ?? "3.60"
        remark = route.remark ?? ""
    }
}
