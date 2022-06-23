//
//  GostXApp.swift
//  GostX
//
//  Created by  on 2022/6/16.
//

import SwiftUI

@main
struct GostXApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    
    var body: some Scene {
        WindowGroup {
            EmptyView()
                .frame(width: .zero)
        }
    }
}
