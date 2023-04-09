//
//  SettingsView.swift
//  GostX
//
//  Created by 刘科彬 on 2022/6/17.
//

import SwiftUI
import HighlightedTextEditor

//let betweenUnderscores = try! NSRegularExpression(pattern: "_[^_]+_", options: [])
let reOpts = NSRegularExpression.Options([.anchorsMatchLines])
let commentRule = try! NSRegularExpression(pattern: "^\\#.*", options: reOpts)
let listenFlagRule = try! NSRegularExpression(pattern: "(\\s+-L\\s+)|(^-L)", options: reOpts)
let forwardFlagRule = try! NSRegularExpression(pattern: "(\\s+-F\\s+)|(^-F)", options: reOpts)

struct SettingsView: View {
    var body: some View {
        TabView {
            ArgumentView()
                .tabItem {
                    Label(NSLocalizedString("Arguments", comment: ""), systemImage: "gear")
                }
                .tag("argument")
        }
        .padding(5)
    }
}

struct ArgumentView: View {
    @AppStorage(defaultsArgumentsKey)
    private var arguments = "-L socks5://:1080"
    
    private let rules: [HighlightRule] = [
        HighlightRule(
            pattern: commentRule,
            formattingRule: TextFormattingRule(key: .foregroundColor, value: NSColor.systemGray)
        ),
        HighlightRule(
            pattern: listenFlagRule,
            formattingRules: [
                TextFormattingRule(fontTraits: .bold),
            ]
        ),
        HighlightRule(
            pattern: forwardFlagRule,
            formattingRules: [
                TextFormattingRule(fontTraits: .bold),
            ]
        ),
    ]
        
    var body: some View {
        Form {
            VStack {
                HighlightedTextEditor(text: $arguments, highlightRules: rules)
                    .introspect { editor in
                        editor.textView.allowsUndo = true
                        editor.textView.updateCandidates()
                    }
            
//            TextEditor(text: $arguments)
//                .padding(5)
//                .cornerRadius(20.0)
//                .shadow(radius: 1.0)
//                .font(Font.system(size: 12).monospaced())
//                .frame(minWidth: 350, minHeight: 200, alignment: .leading)
                
                Text(NSLocalizedString("argument-description", comment: ""))
                    .padding(.horizontal, 5)
                    .font(Font.system(size:12))
                    .foregroundColor(.gray)
            }
        }
    }
}

struct SettingsView_Previews: PreviewProvider {
    static var previews: some View {
        SettingsView()
    }
}
