//
//  SettingsView.swift
//  GostX
//
//  Created by 刘科彬 on 2022/6/17.
//

import SwiftUI

struct SettingsView: View {
    private enum Tabs: Hashable {
        case general, advanced
    }
    var body: some View {
        TabView {
            GeneralSettingsView()
                .tabItem {
                    Label("Arguments", systemImage: "gear")
                }
                .tag(Tabs.general)
        }
        .padding(5)
    }
}

struct GeneralSettingsView: View {
    @AppStorage(defaultsArgumentsKey)
    private var arguments = "-L socks5://:1080"
    
    var body: some View {
        Form {
            if #available(macOS 12.0, *) {
                TextEditor(text: $arguments)
                    .padding(5)
                    .cornerRadius(20.0)
                    .shadow(radius: 1.0)
                    .font(Font.system(size: 12).monospaced())
                    .frame(minWidth: 350, minHeight: 200, alignment: .leading)
            } else {
                TextEditor(text: $arguments)
                    .padding(5)
                    .cornerRadius(20.0)
                    .shadow(radius: 1.0)
                    .font(Font.system(size: 12))
                    .frame(minWidth: 350, minHeight: 200, alignment: .leading)
            }
        }
        
        .padding(5)
    }
}

struct SettingsView_Previews: PreviewProvider {
    static var previews: some View {
        SettingsView()
    }
}
