//
//  SettingsView.swift
//  GostX
//
//  Created by KB on 2022/6/17.
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
        .padding(20)
        .frame(width: 375, height: 150)
    }
}

struct GeneralSettingsView: View {
    @AppStorage(defaultsArgumentsKey)
    private var arguments = "-L socks5://:1080"

    var body: some View {
        Form {
            TextEditor(text: $arguments)
                .padding(5)
                .cornerRadius(20.0)
                .shadow(radius: 1.0)
                .font(Font.system(size: 12).monospaced())
        }
        .padding(5)
    }
}

struct SettingsView_Previews: PreviewProvider {
    static var previews: some View {
        SettingsView()
    }
}
