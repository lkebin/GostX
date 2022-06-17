//
//  GostXApp.swift
//  GostX
//
//  Created by KB on 2022/6/16.
//

import SwiftUI

let defaultsArgumentsKey = "arguments"

@main
struct GostXApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self)
    private var appDelete
    
    var body: some Scene {
        WindowGroup {
            EmptyView()
                .frame(width: .zero)
        }
        WindowGroup{
            SettingsView()
        }.handlesExternalEvents(matching: ["settings"])
    }
}

class AppDelegate: NSObject, NSApplicationDelegate, DaemonProcessDelegate {
    private var menu: MacExtrasConfigurator?
    private var executable: String = "\(Bundle.main.resourcePath!)/gost/gost"
    
    public var process: DaemonProcess?
    
    @Environment(\.openURL)
    private var openURL
    
    func applicationDidFinishLaunching(_ notification: Notification) {
        menu = MacExtrasConfigurator(delegate: self)
        process = DaemonProcess.init(path: self.executable, arguments: "", delegate: self)
        start()
    }
    
    func fetchArguments() -> String? {
        let defaults = UserDefaults.standard
        var a = defaults.string(forKey: defaultsArgumentsKey)
        
        if a == nil {
            a = "-L socks5://:1080"
        }
        
        return a
    }
    
    func process(_: DaemonProcess, isRunning: Bool) {
        if isRunning {
            menu?.statusMenuItem.title = on
            menu?.statusActionOnItem.isEnabled = false
            menu?.statusActionOffItem.isEnabled = true
            menu?.statusBarItem.button?.image = NSImage(
                systemSymbolName: "network.badge.shield.half.filled",
                accessibilityDescription: nil
            )
        } else {
            menu?.statusMenuItem.title = off
            menu?.statusActionOnItem.isEnabled = true
            menu?.statusActionOffItem.isEnabled = false
            menu?.statusBarItem.button?.image = NSImage(
                systemSymbolName: "network",
                accessibilityDescription: nil
            )
        }
    }
    
    func applicationWillTerminate(_ notification: Notification) {
        stop()
    }
    
    func settings() {
        openURL(URL(string: "gostx://settings")!)
    }
    
    func quit() {
        NSApp.terminate(self)
    }
    
    func stop() {
        process?.terminate()
    }
    
    func start() {
        let a = fetchArguments()
        process?.setArguments(a!)
        process?.launch()
    }
}
