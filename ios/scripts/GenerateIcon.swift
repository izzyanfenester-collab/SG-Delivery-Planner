import AppKit
import Foundation

// Preserve existing IZZYAN artwork; convert to Apple's opaque 1024px icon requirement.
let source = CommandLine.arguments[1], output = CommandLine.arguments[2]
guard let image = NSImage(contentsOfFile: source),
      let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 1024, pixelsHigh: 1024, bitsPerSample: 8, samplesPerPixel: 3, hasAlpha: false, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0),
      let context = NSGraphicsContext(bitmapImageRep: bitmap) else { fatalError("Could not open IZZ Delivery branding artwork") }
NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = context
NSColor(red: 0.025, green: 0.07, blue: 0.15, alpha: 1).setFill(); NSRect(x: 0, y: 0, width: 1024, height: 1024).fill()
image.draw(in: NSRect(x: 0, y: 0, width: 1024, height: 1024), from: .zero, operation: .sourceOver, fraction: 1)
context.flushGraphics(); NSGraphicsContext.restoreGraphicsState()
guard let data = bitmap.representation(using: .png, properties: [:]) else { fatalError("Could not encode icon") }
try data.write(to: URL(fileURLWithPath: output), options: .atomic)
