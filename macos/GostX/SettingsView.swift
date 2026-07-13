// macos/GostX/SettingsView.swift
import SwiftUI
import HighlightedTextEditor

let reOpts = NSRegularExpression.Options([.anchorsMatchLines])
let yamlKeyRule   = try! NSRegularExpression(
    pattern: "^(\\s*)(services|chains|hops|name|addr|handler|listener|connector|dialer|type|chain|auth|tls|metadata|bypass|resolver|hosts|retries|timeout)\\s*:",
    options: reOpts)
let yamlCommentRule = try! NSRegularExpression(pattern: "^\\s*#.*", options: reOpts)

struct SettingsView: View {
    var body: some View {
        TabView {
            YamlConfigView()
                .tabItem {
                    Label(NSLocalizedString("Configuration", comment: ""), systemImage: "doc.text")
                }
                .tag("config")
        }
        .padding(5)
    }
}

struct YamlConfigView: View {
    @AppStorage(defaultsYamlKey)
    private var yamlConfig = defaultGostYAML

    private let rules: [HighlightRule] = [
        HighlightRule(
            pattern: yamlCommentRule,
            formattingRule: TextFormattingRule(key: .foregroundColor, value: NSColor.systemGray)
        ),
        HighlightRule(
            pattern: yamlKeyRule,
            formattingRules: [
                TextFormattingRule(fontTraits: .bold),
                TextFormattingRule(key: .foregroundColor, value: NSColor.systemBlue),
            ]
        ),
    ]

    var body: some View {
        VStack {
            HighlightedTextEditor(text: $yamlConfig, highlightRules: rules)
                .introspect { editor in
                    editor.textView.allowsUndo = true
                    editor.textView.breakUndoCoalescing()
                    editor.textView.font = NSFont.monospacedSystemFont(ofSize: 12, weight: .regular)
                }
                .onChange(of: yamlConfig) { newValue in
                    // Sync to App Group for VPN Extension
                    AppGroupConfig.writeYaml(newValue)
                }
            Text("gost v3 YAML configuration — https://gost.run/docs/")
                .padding(.horizontal, 5)
                .font(Font.system(size: 12))
                .foregroundColor(.gray)
        }
    }
}

struct SettingsView_Previews: PreviewProvider {
    static var previews: some View {
        SettingsView()
    }
}
