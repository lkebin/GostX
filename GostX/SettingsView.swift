//
//  SettingsView.swift
//  GostX
//
//  Created by 刘科彬 on 2022/6/17.
//

import SwiftUI

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
    
    var body: some View {
        Form {
            TextEditor(text: $arguments)
                .padding(5)
                .cornerRadius(20.0)
                .shadow(radius: 1.0)
                .font(Font.system(size: 12).monospaced())
                .frame(minWidth: 350, minHeight: 200, alignment: .leading)
            
            Text(NSLocalizedString("argument-description", comment: ""))
                .padding(.horizontal, 5)
                .font(Font.system(size:12))
                .foregroundColor(.gray)
        }
        .padding(5)
    }
}

struct SettingsView_Previews: PreviewProvider {
    static var previews: some View {
        SettingsView()
    }
}
