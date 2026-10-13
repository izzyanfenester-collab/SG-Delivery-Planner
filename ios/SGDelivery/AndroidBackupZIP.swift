import Foundation
import UIKit
import ZIPFoundation

/// Interoperable with Android HistoryBackup.kt ZIP format v1.
/// Private customer records/photos are unencrypted: handle/share these files carefully.
enum AndroidBackupZIP {
    private static let manifestName = "manifest.json"
    private static let routesName = "routes.json"
    private static let maxTotal: Int64 = 350 * 1024 * 1024
    private static let maxRoutes: Int64 = 30 * 1024 * 1024
    private static let maxPicture: Int64 = 20 * 1024 * 1024

    private static func validatePhotoName(_ name: String) -> Bool {
        name.range(of: #"^proofs/[A-Za-z0-9_-]+\.jpg$"#, options: .regularExpression) != nil
    }

    private static func add(_ data: Data, name: String, to archive: Archive) throws {
        try archive.addEntry(with: name, type: .file, uncompressedSize: Int64(data.count),
                             compressionMethod: .deflate, provider: { position, count in
            guard position >= 0, count >= 0, position <= Int64(data.count),
                  Int64(count) <= Int64(data.count) - position else {
                throw DeliveryError.invalid("Invalid backup output offset")
            }
            return data.subdata(in: Int(position)..<(Int(position) + count))
        })
    }

    static func exportArchive(_ routes: [DeliveryRoute]) throws -> URL {
        let converted = try AndroidHistoryFormat.exportRows(routes)
        guard converted.routesData.count <= Int(maxRoutes) else {
            throw DeliveryError.invalid("Route history exceeds Android backup limit.")
        }
        let rows = try JSONSerialization.jsonObject(with: converted.routesData) as! [[String: Any]]
        let manifest: [String: Any] = [
            "format": "sg-delivery-history", "version": 1,
            "routeCount": rows.count, "exportedAt": AndroidHistoryFormat.local(Date())
        ]
        let manifestData = try JSONSerialization.data(withJSONObject: manifest)
        let destination = FileManager.default.temporaryDirectory.appendingPathComponent(
            "SG-Delivery-Android-Compatible-\(UUID().uuidString).zip")
        guard let archive = Archive(url: destination, accessMode: .create) else {
            throw DeliveryError.invalid("Could not create Android-compatible ZIP backup.")
        }
        try add(manifestData, name: manifestName, to: archive)
        try add(converted.routesData, name: routesName, to: archive)
        for (id, bytes) in converted.photos.sorted(by: { $0.key < $1.key }) {
            guard validatePhotoName("proofs/" + id), Int64(bytes.count) <= maxPicture else {
                throw DeliveryError.invalid("Invalid proof photo filename or size.")
            }
            try add(bytes, name: "proofs/" + id, to: archive)
        }
        return destination
    }

    /// Resample Android JPGs to fit the iPhone's per-parcel 1MB safe storage limit.
    private static func proofData(_ data: Data) throws -> Data {
        guard let image = UIImage(data: data) else {
            throw DeliveryError.invalid("Backup contains an unreadable proof image.")
        }
        if let jpeg = ProofImage.encode(image), jpeg.count <= 1_000_000 { return jpeg }
        let maxEdge: CGFloat = 900
        let factor = min(1, maxEdge / max(image.size.width, image.size.height))
        let size = CGSize(width: max(1, image.size.width * factor),
                          height: max(1, image.size.height * factor))
        let painter = UIGraphicsImageRenderer(size: size)
        let resized = painter.image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let jpeg = resized.jpegData(compressionQuality: 0.38),
              jpeg.count <= 1_000_000 else {
            throw DeliveryError.invalid("Proof photo could not be compressed below the iOS storage limit.")
        }
        return jpeg
    }

    static func importArchive(_ input: URL) throws -> [DeliveryRoute] {
        guard let archive = Archive(url: input, accessMode: .read) else {
            throw DeliveryError.invalid("Invalid ZIP file.")
        }
        var seen = Set<String>()
        var manifest: Data?
        var routes: Data?
        var photos: [String: Data] = [:]
        var aggregate: Int64 = 0
        for entry in archive {
            let name = entry.path
            guard entry.type == .file, seen.insert(name).inserted else {
                throw DeliveryError.invalid("Duplicate or invalid Android backup entry.")
            }
            let limit: Int64
            if name == manifestName { limit = 32_768 }
            else if name == routesName { limit = maxRoutes }
            else if validatePhotoName(name) {
                guard photos.count < 5_000 else {
                    throw DeliveryError.invalid("Backup exceeds maximum proof file count.")
                }
                limit = maxPicture
            } else { throw DeliveryError.invalid("Unexpected filename in Android ZIP backup.") }

            let length = Int64(entry.uncompressedSize)
            guard length >= 0, length <= limit, aggregate <= maxTotal - length else {
                throw DeliveryError.invalid("Android backup is larger than permitted limits.")
            }
            aggregate += length
            var data = Data()
            let _ = try archive.extract(entry, consumer: { chunk in
                guard data.count + chunk.count <= Int(limit) else {
                    throw DeliveryError.invalid("Backup ZIP decompression limit exceeded.")
                }
                data.append(chunk)
            })
            if name == manifestName { manifest = data }
            else if name == routesName { routes = data }
            else {
                let key = String(name.dropFirst("proofs/".count))
                photos[key] = try proofData(data)
            }
        }
        guard let manifest, let routes,
              let header = try JSONSerialization.jsonObject(with: manifest) as? [String: Any],
              header["format"] as? String == "sg-delivery-history",
              (header["version"] as? NSNumber)?.intValue == 1,
              let count = (header["routeCount"] as? NSNumber)?.intValue,
              count >= 0, count <= 2000 else {
            throw DeliveryError.invalid("ZIP manifest is not Android SG Delivery history v1.")
        }
        let restored = try AndroidHistoryFormat.readRoutes(routes, photos: photos)
        guard restored.count == count else {
            throw DeliveryError.invalid("Backup route count does not match manifest.")
        }
        return restored
    }
}
