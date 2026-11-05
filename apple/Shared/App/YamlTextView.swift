import Foundation
import SwiftUI

#if os(macOS)
import AppKit
typealias PlatformFont = NSFont
typealias PlatformColor = NSColor
typealias PlatformTextStorageEditActions = NSTextStorageEditActions
#else
import UIKit
typealias PlatformFont = UIFont
typealias PlatformColor = UIColor
typealias PlatformTextStorageEditActions = NSTextStorage.EditActions
#endif

/// Token classes the YAML highlighter recognises.
enum YamlTokenKind: CaseIterable {
    case comment
    case string
    case number
    case boolean
    case key
}

/// Platform-neutral YAML syntax highlighting shared by the macOS and iOS
/// editors. Only fonts and colours differ per platform.
enum YamlHighlighting {
    #if os(iOS)
    static let fontSize: CGFloat = 13
    #else
    static let fontSize: CGFloat = 12
    #endif

    static var baseFont: PlatformFont {
        PlatformFont.monospacedSystemFont(ofSize: fontSize, weight: .regular)
    }

    static var boldFont: PlatformFont {
        PlatformFont.monospacedSystemFont(ofSize: fontSize, weight: .bold)
    }

    static var baseColor: PlatformColor {
        #if os(macOS)
        return .textColor
        #else
        return .label
        #endif
    }

    /// Patterns are ordered by application priority: a later rule overrides an
    /// earlier one on the same characters (comments are applied first so the
    /// other token rules skip anything inside them).
    static let patterns: [(kind: YamlTokenKind, pattern: String)] = [
        // comment
        (.comment, "#.*"),
        // string (double/single quoted)
        (.string, "\"[^\"]*\"|'[^']*'"),
        // number (integer / float)
        (.number, "(?<![\\w#])\\b\\d+\\.?\\d*\\b"),
        // boolean / null
        (.boolean, "(?<![\\w#])\\b(true|false|yes|no|on|off|null|~)\\b"),
        // key
        (.key, "^\\s*(?:- )?[\\w-]+\\s*:(?=\\s|$)"),
    ]

    private static let compiled: [(kind: YamlTokenKind, regex: NSRegularExpression)] = {
        patterns.compactMap { entry in
            guard let regex = try? NSRegularExpression(pattern: entry.pattern,
                                                      options: [.anchorsMatchLines]) else {
                return nil
            }
            return (entry.kind, regex)
        }
    }()

    /// All highlight spans in `text`, in application order.
    ///
    /// Matches that fall inside a comment are dropped so that punctuation
    /// inside a comment (quotes, numbers) keeps the comment colour.
    static func spans(in text: String) -> [(range: NSRange, kind: YamlTokenKind)] {
        let fullRange = NSRange(location: 0, length: (text as NSString).length)
        guard fullRange.length > 0 else { return [] }

        var comments: [NSRange] = []
        for entry in compiled where entry.kind == .comment {
            entry.regex.enumerateMatches(in: text, options: [], range: fullRange) { match, _, _ in
                if let range = match?.range {
                    comments.append(range)
                }
            }
        }

        var result: [(range: NSRange, kind: YamlTokenKind)] = []
        for entry in compiled {
            entry.regex.enumerateMatches(in: text, options: [], range: fullRange) { match, _, _ in
                guard let range = match?.range else { return }
                if entry.kind != .comment,
                   comments.contains(where: { NSIntersectionRange($0, range).length > 0 }) {
                    return
                }
                result.append((range, entry.kind))
            }
        }
        return result
    }

    static func attributes(for kind: YamlTokenKind) -> [NSAttributedString.Key: Any] {
        let commentColor = PlatformColor(red: 0.5, green: 0.5, blue: 0.5, alpha: 1.0)
        let keyColor = PlatformColor(red: 0.2, green: 0.4, blue: 0.8, alpha: 1.0)
        let stringColor = PlatformColor(red: 0.1, green: 0.6, blue: 0.1, alpha: 1.0)
        let numberColor = PlatformColor(red: 0.9, green: 0.5, blue: 0.1, alpha: 1.0)
        let booleanColor = PlatformColor(red: 0.6, green: 0.2, blue: 0.8, alpha: 1.0)

        switch kind {
        case .comment: return [.foregroundColor: commentColor, .font: baseFont]
        case .string: return [.foregroundColor: stringColor, .font: baseFont]
        case .number: return [.foregroundColor: numberColor, .font: baseFont]
        case .boolean: return [.foregroundColor: booleanColor, .font: baseFont]
        case .key: return [.foregroundColor: keyColor, .font: boldFont]
        }
    }

    /// Repaints `storage` in place. Attribute-only edits, so the text view's
    /// undo stack and selection are untouched.
    static func apply(to storage: NSTextStorage) {
        let text = storage.string
        let fullRange = NSRange(location: 0, length: storage.length)
        guard fullRange.length > 0 else { return }

        storage.beginEditing()
        storage.addAttributes([.font: baseFont, .foregroundColor: baseColor], range: fullRange)
        for span in spans(in: text) {
            storage.addAttributes(attributes(for: span.kind), range: span.range)
        }
        storage.endEditing()
    }
}

/// YAML editor with live syntax highlighting, shared by the macOS settings
/// window and the iOS profile screen. Each platform supplies its own text view
/// through the matching `*Representable` conformance below.
struct YamlTextView {
    @Binding var text: String

    /// Keeps `NSTextStorage` highlighting from re-entering itself and mirrors
    /// user edits back into the SwiftUI binding.
    final class Coordinator: NSObject, NSTextStorageDelegate {
        var parent: YamlTextView
        var updating = false
        var needsBindingSync = false

        init(_ parent: YamlTextView) {
            self.parent = parent
        }

        func textStorage(_ textStorage: NSTextStorage,
                         didProcessEditing editedMask: PlatformTextStorageEditActions,
                         range editedRange: NSRange,
                         changeInLength delta: Int) {
            guard !updating else { return }

            updating = true
            YamlHighlighting.apply(to: textStorage)
            updating = false

            if needsBindingSync {
                parent.text = textStorage.string
            }
        }
    }
}

#if os(macOS)
extension YamlTextView: NSViewRepresentable {
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeNSView(context: Context) -> NSScrollView {
        let scrollView = NSTextView.scrollableTextView()
        guard let textView = scrollView.documentView as? NSTextView else { return scrollView }

        textView.font = YamlHighlighting.baseFont
        textView.isAutomaticTextReplacementEnabled = false
        textView.allowsUndo = true
        textView.isEditable = true
        textView.isRichText = false
        textView.textContainer?.widthTracksTextView = true
        textView.textContainer?.containerSize = NSSize(width: CGFloat.greatestFiniteMagnitude,
                                                       height: CGFloat.greatestFiniteMagnitude)
        textView.textContainerInset = NSSize(width: 4, height: 4)

        // Use the text storage delegate for highlighting — it does not
        // interfere with NSTextViewDelegate.
        textView.textStorage?.delegate = context.coordinator

        context.coordinator.needsBindingSync = false
        textView.string = text
        if let storage = textView.textStorage {
            YamlHighlighting.apply(to: storage)
        }
        // From here on every text change originates from user editing, so it
        // must be propagated to the binding. Enabling this in updateNSView
        // instead fails when the editor is created with text already equal to
        // the binding value (e.g. reopening after a save), which silently drops
        // all subsequent keystrokes.
        context.coordinator.needsBindingSync = true

        return scrollView
    }

    func updateNSView(_ scrollView: NSScrollView, context: Context) {
        guard let textView = scrollView.documentView as? NSTextView else { return }
        let coordinator = context.coordinator

        // Only update when the binding changed externally (e.g. profile switch).
        guard textView.string != text else { return }
        coordinator.needsBindingSync = false
        coordinator.updating = true
        textView.string = text
        if let storage = textView.textStorage {
            YamlHighlighting.apply(to: storage)
        }
        coordinator.updating = false
        coordinator.needsBindingSync = true
    }
}
#else
extension YamlTextView: UIViewRepresentable {
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIView(context: Context) -> UITextView {
        let textView = UITextView()
        textView.font = YamlHighlighting.baseFont
        textView.isEditable = true
        textView.isScrollEnabled = true
        textView.alwaysBounceVertical = true
        // Smart punctuation would rewrite YAML quotes and dashes.
        textView.autocorrectionType = .no
        textView.autocapitalizationType = .none
        textView.smartQuotesType = .no
        textView.smartDashesType = .no
        textView.smartInsertDeleteType = .no
        textView.spellCheckingType = .no
        textView.textContainerInset = UIEdgeInsets(top: 8, left: 8, bottom: 8, right: 8)
        textView.backgroundColor = .clear

        textView.textStorage.delegate = context.coordinator

        context.coordinator.needsBindingSync = false
        textView.text = text
        YamlHighlighting.apply(to: textView.textStorage)
        // See the macOS note: user edits must flow back to the binding only
        // after the initial content has been installed.
        context.coordinator.needsBindingSync = true

        return textView
    }

    func updateUIView(_ textView: UITextView, context: Context) {
        let coordinator = context.coordinator

        guard textView.text != text else { return }
        coordinator.needsBindingSync = false
        coordinator.updating = true
        textView.text = text
        YamlHighlighting.apply(to: textView.textStorage)
        coordinator.updating = false
        coordinator.needsBindingSync = true
    }
}
#endif