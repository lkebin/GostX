// macos/GostX/LogContentView.swift
import SwiftUI
import AppKit

// MARK: - NSTextView Wrapper

/// Read-only NSTextView with monospaced font, native scrolling and text selection.
@available(macOS 14.0, *)
private struct LogTextView: NSViewRepresentable {
    @Binding var text: String
    var isFollowing: Bool
    var onScrollToTop: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onScrollToTop: onScrollToTop)
    }

    func makeNSView(context: Context) -> NSScrollView {
        let scrollView = NSTextView.scrollableTextView()
        let textView = scrollView.documentView as! NSTextView

        let defaultAttrs: [NSAttributedString.Key: Any] = [
            .foregroundColor: NSColor.textColor,
            .font: NSFont.monospacedSystemFont(ofSize: 11, weight: .regular),
        ]
        context.coordinator.defaultAttrs = defaultAttrs

        textView.backgroundColor = .textBackgroundColor
        textView.isEditable = false
        textView.isSelectable = true
        textView.isRichText = false
        textView.textContainerInset = NSSize(width: 8, height: 8)
        textView.textContainer?.widthTracksTextView = true
        textView.textContainer?.containerSize = NSSize(
            width: CGFloat.greatestFiniteMagnitude,
            height: CGFloat.greatestFiniteMagnitude
        )

        context.coordinator.scrollView = scrollView

        // Track scroll position for infinite scroll up
        scrollView.contentView.postsBoundsChangedNotifications = true
        NotificationCenter.default.addObserver(
            context.coordinator,
            selector: #selector(Coordinator.boundsDidChange(_:)),
            name: NSView.boundsDidChangeNotification,
            object: scrollView.contentView
        )

        return scrollView
    }

    func updateNSView(_ scrollView: NSScrollView, context: Context) {
        let textView = scrollView.documentView as! NSTextView
        let oldText = textView.string
        guard oldText != text else { return }

        // Fast path: new text extends old text (polling append)
        if text.hasPrefix(oldText) {
            let suffix = String(text.dropFirst(oldText.count))
            textView.textStorage?.append(NSAttributedString(string: suffix, attributes: context.coordinator.defaultAttrs))
            if isFollowing {
                context.coordinator.scrollToBottom()
            }
            return
        }

        // Slow path: full replace (initial load, truncation, history prepend)
        let oldHeight = scrollView.contentView.bounds.height
        let oldScrollY = scrollView.contentView.bounds.origin.y
        let wasAtTop = oldScrollY <= 0

        textView.textStorage?.setAttributedString(NSAttributedString(string: text, attributes: context.coordinator.defaultAttrs))

        if !wasAtTop {
            let newHeight = scrollView.contentView.bounds.height
            let delta = newHeight - oldHeight
            scrollView.contentView.scroll(to: NSPoint(x: 0, y: oldScrollY + delta))
        } else if isFollowing {
            context.coordinator.scrollToBottom()
        }
    }

    class Coordinator: NSObject {
        let onScrollToTop: () -> Void
        private var lastScrollY: CGFloat = 0
        weak var scrollView: NSScrollView?
        var defaultAttrs: [NSAttributedString.Key: Any] = [:]

        init(onScrollToTop: @escaping () -> Void) {
            self.onScrollToTop = onScrollToTop
        }

        func scrollToBottom() {
            guard let sv = scrollView,
                  let textView = sv.documentView as? NSTextView else { return }
            let maxY = max(0, textView.bounds.height - sv.contentView.bounds.height)
            sv.contentView.scroll(to: NSPoint(x: 0, y: maxY))
        }

        @objc func boundsDidChange(_ notification: Notification) {
            guard let clipView = notification.object as? NSClipView else { return }
            let scrollY = clipView.bounds.origin.y
            if scrollY <= 5 && lastScrollY > 5 {
                onScrollToTop()
            }
            lastScrollY = scrollY
        }
    }
}

// MARK: - Log Content View

@available(macOS 14.0, *)
struct LogContentView: View {
    @ObservedObject var vm: LogViewModel
    let loggingEnabled: Bool

    var body: some View {
        VStack(spacing: 0) {
            if !loggingEnabled {
                LogPlaceholder(icon: "text.alignleft", text: NSLocalizedString("Logging is off", comment: ""))
            } else if vm.lines.isEmpty {
                LogPlaceholder(icon: "text.alignleft", text: NSLocalizedString("No logs", comment: ""))
            } else {
                LogTextView(
                    text: Binding(
                        get: { vm.lines.joined(separator: "\n") },
                        set: { _ in }
                    ),
                    isFollowing: vm.isFollowing,
                    onScrollToTop: { vm.loadMoreHistory() }
                )
            }

            // Bottom toolbar
            if loggingEnabled {
                Divider()

                HStack(spacing: 8) {
                    Button(action: { vm.isFollowing.toggle() }) {
                        Image(systemName: vm.isFollowing ? "pause.fill" : "play.fill")
                    }
                    .buttonStyle(.borderless)
                    .help(vm.isFollowing
                        ? NSLocalizedString("Pause auto-scroll", comment: "")
                        : NSLocalizedString("Resume auto-scroll", comment: ""))

                    Divider()
                        .frame(height: 16)

                    Button(action: { vm.copyAll() }) {
                        Image(systemName: "doc.on.doc")
                    }
                    .buttonStyle(.borderless)
                    .help(NSLocalizedString("Copy all", comment: ""))

                    Button(action: { vm.clearLog() }) {
                        Image(systemName: "trash")
                    }
                    .buttonStyle(.borderless)
                    .help(NSLocalizedString("Clear log", comment: ""))

                    Spacer()

                    Text("\(vm.lines.count) lines")
                        .font(.system(size: 10))
                        .foregroundColor(.secondary)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .frame(height: 32)
            }
        }
        .ignoresSafeArea(.container, edges: .top)
        .onAppear { vm.onAppear(loggingEnabled: loggingEnabled) }
        .onDisappear { vm.onDisappear() }
    }
}

@available(macOS 14.0, *)
private struct LogPlaceholder: View {
    let icon: String
    let text: String

    var body: some View {
        VStack {
            Spacer()
            Image(systemName: icon)
                .font(.system(size: 28))
                .foregroundColor(Color(nsColor: .tertiaryLabelColor))
            Text(text)
                .font(.system(size: 13))
                .foregroundColor(.secondary)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
