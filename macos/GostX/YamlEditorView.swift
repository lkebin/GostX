//
//  YamlEditorView.swift
//  GostX
//
//  SwiftUI wrapper around NSTextView with real-time YAML syntax highlighting.
//  Supports undo naturally because it never replaces the entire NSTextStorage.
//

import SwiftUI
import AppKit

/// A YAML text editor with syntax highlighting and native undo support.
struct YamlTextView: NSViewRepresentable {
    @Binding var text: String

    // MARK: - YAML Syntax Highlighting

    private static let rules: [(NSRegularExpression, [NSAttributedString.Key: Any])] = {
        let commentFont = NSFont.monospacedSystemFont(ofSize: 12, weight: .regular)
        let keyFont = NSFont.monospacedSystemFont(ofSize: 12, weight: .bold)
        let valueFont = NSFont.monospacedSystemFont(ofSize: 12, weight: .regular)

        let commentColor = NSColor(red: 0.5, green: 0.5, blue: 0.5, alpha: 1.0)
        let keyColor = NSColor(red: 0.2, green: 0.4, blue: 0.8, alpha: 1.0)
        let stringColor = NSColor(red: 0.1, green: 0.6, blue: 0.1, alpha: 1.0)
        let numberColor = NSColor(red: 0.9, green: 0.5, blue: 0.1, alpha: 1.0)
        let boolColor = NSColor(red: 0.6, green: 0.2, blue: 0.8, alpha: 1.0)

        let patterns: [(String, [NSAttributedString.Key: Any])] = [
            // comment — must be first to avoid matching inside comments
            ("#.*", [.foregroundColor: commentColor, .font: commentFont]),
            // string (double/single-quoted)
            ("\"[^\"]*\"|'[^']*'", [.foregroundColor: stringColor, .font: valueFont]),
            // number (integer / float)
            ("(?<![\\w#])\\b\\d+\\.?\\d*\\b", [.foregroundColor: numberColor, .font: valueFont]),
            // boolean / null
            ("(?<![\\w#])\\b(true|false|yes|no|on|off|null|~)\\b", [.foregroundColor: boolColor, .font: valueFont]),
            // key
            ("^\\s*(?:- )?[\\w-]+\\s*:(?=\\s|$)", [.foregroundColor: keyColor, .font: keyFont]),
        ]

        return patterns.map { (pattern, attrs) in
            (try! NSRegularExpression(pattern: pattern, options: [.anchorsMatchLines]), attrs)
        }
    }()

    private static func applyHighlighting(_ text: String, to storage: NSTextStorage) {
        let fullRange = NSRange(location: 0, length: storage.length)
        let defaultFont = NSFont.monospacedSystemFont(ofSize: 12, weight: .regular)
        let defaultColor = NSColor.textColor

        // Reset all text to default style
        storage.addAttribute(.font, value: defaultFont, range: fullRange)
        storage.addAttribute(.foregroundColor, value: defaultColor, range: fullRange)

        // Apply each rule
        for (regex, attrs) in rules {
            regex.enumerateMatches(in: text, options: [], range: NSRange(location: 0, length: text.utf16.count)) { match, _, _ in
                guard let range = match?.range else { return }
                for (key, value) in attrs {
                    storage.addAttribute(key, value: value, range: range)
                }
            }
        }
    }

    // MARK: - Coordinator

    class Coordinator: NSObject, NSTextStorageDelegate {
        var parent: YamlTextView
        var updating = false
        var needsBindingSync = false

        init(_ parent: YamlTextView) {
            self.parent = parent
        }

        func textStorage(_ textStorage: NSTextStorage, didProcessEditing editedMask: NSTextStorageEditActions, range editedRange: NSRange, changeInLength delta: Int) {
            guard !updating else { return }
            let content = textStorage.string

            // Re-highlight, with guard to prevent re-entry
            updating = true
            YamlTextView.applyHighlighting(content, to: textStorage)
            updating = false

            // Sync to SwiftUI binding (skip during initial setup)
            if needsBindingSync {
                parent.text = content
            }
        }
    }

    // MARK: - NSViewRepresentable

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeNSView(context: Context) -> NSScrollView {
        let scrollView = NSTextView.scrollableTextView()
        let textView = scrollView.documentView as! NSTextView

        textView.font = NSFont.monospacedSystemFont(ofSize: 12, weight: .regular)
        textView.isAutomaticTextReplacementEnabled = false
        textView.allowsUndo = true
        textView.isEditable = true
        textView.isRichText = false
        textView.textContainer?.widthTracksTextView = true
        textView.textContainer?.containerSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
        textView.textContainerInset = NSSize(width: 4, height: 4)

        // Use textStorage delegate for highlighting — doesn't interfere with NSTextViewDelegate
        textView.textStorage?.delegate = context.coordinator

        // Initial content
        textView.string = text
        YamlTextView.applyHighlighting(text, to: textView.textStorage!)

        return scrollView
    }

    func updateNSView(_ scrollView: NSScrollView, context: Context) {
        let textView = scrollView.documentView as! NSTextView
        let coordinator = context.coordinator

        // Only update when binding changed externally (e.g., load profile)
        guard textView.string != text else { return }
        coordinator.needsBindingSync = false  // suppress didProcessEditing binding sync
        coordinator.updating = true
        textView.string = text
        YamlTextView.applyHighlighting(text, to: textView.textStorage!)
        coordinator.updating = false
        coordinator.needsBindingSync = true   // enable user-editing binding sync
    }
}
