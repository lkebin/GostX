//
//  GostXApp.swift
//  GostX
//
//  Created by 刘科彬 on 2022/6/16.
//

import SwiftUI
import os
    
let defaultsArgumentsKey = "arguments"
let logger = Logger(subsystem: Bundle.main.bundleIdentifier!, category: "runtime")

@main
struct GostXApp: App {
    @Environment(\.scenePhase) private var scenePhase
    
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelete
    
    var body: some Scene {
        WindowGroup {
            EmptyView()
                .frame(width: .zero)
        }
    }
}

class AppDelegate: NSObject, NSApplicationDelegate, DaemonProcessDelegate {
    private var menu: MacExtrasConfigurator?
    private var executable: String = "\(Bundle.main.resourcePath!)/gost/gost"
    
    public var process: DaemonProcess?
    
    private var window: NSWindow?
    
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
        if window == nil {
            window = NSWindow()
            window?.isReleasedWhenClosed = false
            window?.toolbarStyle = .unifiedCompact
            window?.contentView = NSHostingView(rootView: SettingsView())
            window?.center()
            window?.styleMask.insert(.closable)
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(self)
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
