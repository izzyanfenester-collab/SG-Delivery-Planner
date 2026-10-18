import SwiftUI
import UIKit

/// Uses the real device camera when available. Simulator can continue with PhotosPicker.
struct ProofCameraPicker: UIViewControllerRepresentable {
    let onCapture: (UIImage) -> Void
    let onCancel: () -> Void

    final class Coordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate {
        let parent: ProofCameraPicker
        init(parent: ProofCameraPicker) { self.parent = parent }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.onCancel()
        }
        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = (info[.editedImage] ?? info[.originalImage]) as? UIImage {
                parent.onCapture(image)
            } else { parent.onCancel() }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera) ? .camera : .photoLibrary
        picker.cameraCaptureMode = picker.sourceType == .camera ? .photo : .photo
        picker.allowsEditing = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
}

enum ProofImage {
    /// Store an orientation-normalized JPEG for both captured photos and library photos.
    static func encode(_ image: UIImage) -> Data? {
        let maximum = max(image.size.width, image.size.height)
        guard maximum > 0 else { return nil }
        let scale = min(1.0, 1600.0 / maximum)
        let size = CGSize(width: max(1, image.size.width * scale),
                          height: max(1, image.size.height * scale))
        let renderer = UIGraphicsImageRenderer(size: size)
        let clean = renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        return clean.jpegData(compressionQuality: 0.68)
    }
}
