import Foundation
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// Cross-platform clipboard helper: NSPasteboard on macOS, UIPasteboard on iOS.
enum Clipboard {
    static func copy(_ text: String) {
        #if os(macOS)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #else
        UIPasteboard.general.string = text
        #endif
    }
}
