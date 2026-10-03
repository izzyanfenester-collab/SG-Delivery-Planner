import SwiftUI

enum Brand {
    static let navy = Color(red: 0.025, green: 0.07, blue: 0.15)
    static let card = Color(red: 0.055, green: 0.12, blue: 0.23)
    static let gold = Color(red: 0.87, green: 0.70, blue: 0.37)
    static func status(_ status: String) -> Color {
        switch status { case "DELIVERED": return .green; case "ON_HOLD": return .orange; case "SKIPPED": return .gray; default: return .blue }
    }
}
struct GoldButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.headline).foregroundStyle(Brand.navy).padding(.vertical, 14).frame(maxWidth: .infinity)
            .background(Brand.gold.opacity(configuration.isPressed ? 0.7 : 1), in: RoundedRectangle(cornerRadius: 16))
    }
}
extension View {
    func premiumCard() -> some View { padding(18).frame(maxWidth: .infinity, alignment: .leading).background(Brand.card, in: RoundedRectangle(cornerRadius: 20)) }
    func screenBackground() -> some View { background(Brand.navy).scrollContentBackground(.hidden) }
}
struct ChatGPTButton: View {
    @EnvironmentObject var store: AppStore
    var body: some View { Button { store.open(URL(string: "https://chatgpt.com/")!) } label: { Label("Ask ChatGPT", systemImage: "bubble.left.and.bubble.right") }.buttonStyle(.bordered).tint(Brand.gold) }
}
