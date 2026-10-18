import SwiftUI

enum SGStyle {
    static let navy = Color(red: 7/255, green: 23/255, blue: 45/255)
    static let royal = Color(red: 24/255, green: 60/255, blue: 120/255)
    static let gold = Color(red: 228/255, green: 188/255, blue: 105/255)
    static let card = Color(red: 16/255, green: 35/255, blue: 62/255)
    static let cardAlt = Color(red: 27/255, green: 48/255, blue: 80/255)
    static let text = Color(red: 243/255, green: 246/255, blue: 252/255)
    static let muted = Color(red: 187/255, green: 201/255, blue: 221/255)
    static let outline = Color(red: 48/255, green: 69/255, blue: 99/255)
    static let blue = Color(red: 155/255, green: 190/255, blue: 250/255)
    static let green = Color(red: 18/255, green: 107/255, blue: 80/255)
    static let amber = Color(red: 244/255, green: 180/255, blue: 85/255)
    static let error = Color(red: 93/255, green: 26/255, blue: 26/255)
}

struct SGCard<Content: View>: View {
    var color: Color = SGStyle.card
    var stroke: Color? = nil
    @ViewBuilder let content: Content
    var body: some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(color, in: RoundedRectangle(cornerRadius: 24))
            .overlay(RoundedRectangle(cornerRadius: 24).stroke(stroke ?? SGStyle.outline, lineWidth: 0.7))
    }
}
struct SGAction: View {
    let title: String
    var filled: Bool = true
    var enabled: Bool = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.system(size: 15, weight: .semibold, design: .rounded))
                .frame(maxWidth: .infinity, minHeight: 56)
                .foregroundStyle(filled ? SGStyle.navy : SGStyle.blue)
                .background(filled ? SGStyle.blue : Color.clear,
                            in: RoundedRectangle(cornerRadius: 22))
                .overlay(RoundedRectangle(cornerRadius: 22)
                    .stroke(filled ? Color.clear : SGStyle.outline, lineWidth: 1.3))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
    }
}
struct SGMetric: View {
    let value: String
    let title: String
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(value).font(.system(size: 33, weight: .bold, design: .rounded))
                .foregroundStyle(SGStyle.text)
            Text(title).font(.system(size: 13, weight: .semibold, design: .serif))
                .foregroundStyle(SGStyle.muted)
        }
        .frame(maxWidth: .infinity, minHeight: 92, alignment: .leading)
        .padding(16)
        .background(SGStyle.cardAlt, in: RoundedRectangle(cornerRadius: 22))
    }
}
struct SGHeading: View {
    let title: String
    var subtitle: String = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title).font(.system(size: 25, weight: .bold, design: .rounded))
                .foregroundStyle(SGStyle.text)
            if !subtitle.isEmpty {
                Text(subtitle).font(.footnote).foregroundStyle(SGStyle.muted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
struct SGStatus: View {
    let status: DeliveryStatus
    var body: some View {
        Text(status.rawValue.uppercased())
            .font(.system(size: 10, weight: .bold))
            .padding(.horizontal, 10).padding(.vertical, 6)
            .foregroundStyle(SGStyle.text)
            .background(status == .delivered ? SGStyle.green :
                        status == .hold ? SGStyle.amber.opacity(0.6) :
                        status == .skipped ? SGStyle.outline : SGStyle.royal,
                        in: Capsule())
    }
}
struct SGField: View {
    let title: String
    let value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(SGStyle.muted)
            Text(value.isEmpty ? "—" : value).font(.subheadline)
                .foregroundStyle(SGStyle.text)
        }
    }
}
