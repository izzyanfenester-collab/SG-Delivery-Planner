import SwiftUI
import UniformTypeIdentifiers

struct ExcelDocument: FileDocument {
    static var readableContentTypes: [UTType] { [UTType(filenameExtension: "xlsx") ?? .data] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}
struct SharePayload: Identifiable { let id = UUID(); let items: [Any] }
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
struct ExportActions: View {
    @EnvironmentObject var store: AppStore
    let route: Route
    @State private var document: ExcelDocument?
    @State private var exporting = false
    @State private var sharing: SharePayload?
    @State private var temporaryDirectory: URL?
    var body: some View {
        VStack(spacing: 12) {
            Button {
                do { document = ExcelDocument(data: try ExcelWorkbook.make(route)); exporting = true }
                catch { store.error = store.userMessage(error) }
            } label: { Label("Export Excel", systemImage: "arrow.down.document") }.buttonStyle(GoldButton())
            Button {
                do {
                    cleanup()
                    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
                    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
                    temporaryDirectory = directory
                    let url = directory.appendingPathComponent(ExcelWorkbook.filename(route))
                    try ExcelWorkbook.make(route).write(to: url, options: [.atomic, .completeFileProtection])
                    sharing = SharePayload(items: [url])
                } catch { cleanup(); store.error = store.userMessage(error) }
            } label: { Label("Share Excel", systemImage: "square.and.arrow.up") }.buttonStyle(.bordered)
        }
        .fileExporter(isPresented: $exporting, document: document, contentType: ExcelDocument.readableContentTypes[0], defaultFilename: ExcelWorkbook.filename(route)) { result in
            switch result { case .success: store.notice = "Excel file saved successfully"; case .failure(let error): store.error = "Excel could not be saved: \(error.localizedDescription)" }
        }
        .sheet(item: $sharing, onDismiss: cleanup) { ShareSheet(items: $0.items) }
    }
    private func cleanup() { if let directory = temporaryDirectory { try? FileManager.default.removeItem(at: directory) }; temporaryDirectory = nil }
}
