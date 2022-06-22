//
//  GostXApp.swift
//  GostX
//
//  Created by 刘科彬 on 2022/6/16.
//

import SwiftUI
import os
import Gost

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

class AppDelegate: NSObject, NSApplicationDelegate {
    private var menu: MacExtrasConfigurator?
    private var executable: String = "\(Bundle.main.resourcePath!)/gost/gost"
    private var logPipe: Pipe?
    private var window: NSWindow?
    
    func applicationDidFinishLaunching(_ notification: Notification) {
        self.logPipe = pipe()
        self.menu = MacExtrasConfigurator(delegate: self)
        self.start()
    }
    
    func applicationWillTerminate(_ notification: Notification) {
        self.stop()
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
        gostStop()
        
        menu?.statusMenuItem.title = off
        menu?.statusActionOnItem.isEnabled = true
        menu?.statusActionOffItem.isEnabled = false
        menu?.statusBarItem.button?.image = NSImage(
            systemSymbolName: "network",
            accessibilityDescription: nil
        )
    }
    
    func start() -> () {
        let args: NSString = parseArguments(fetchArguments()) as NSString
        var fd = self.logPipe?.fileHandleForWriting.fileDescriptor
        let fdPtr = UnsafeMutablePointer<CLong>.allocate(capacity: 1)
        withUnsafeMutablePointer(to: &fd, { (ptr: UnsafeMutablePointer<Int32?>) in
            fdPtr.initialize(to: CLong(ptr.pointee!))
        })

        let isFailed = gostRun(UnsafeMutablePointer<CChar>(mutating: args.utf8String),UnsafeMutablePointer<CLong>(mutating: fdPtr))
        if (isFailed != 0) {
            let n = NSUserNotification()
            n.title = "GostX service run failed! Please check logs for detail"
            n.soundName = NSUserNotificationDefaultSoundName
            NSUserNotificationCenter.default.deliver(n)
            return
        }
        
        menu?.statusMenuItem.title = on
        menu?.statusActionOnItem.isEnabled = false
        menu?.statusActionOffItem.isEnabled = true
        menu?.statusBarItem.button?.image = NSImage(
            systemSymbolName: "network.badge.shield.half.filled",
            accessibilityDescription: nil
        )
    }
    
    private func parseArguments(_ v: String) -> String {
        if !v.isEmpty {
            var sep: CharacterSet = CharacterSet.whitespaces
            sep = sep.union(CharacterSet.newlines)
            return v.components(separatedBy: sep).filter { e in
                return !e.isEmpty
            }.joined(separator: " ")
        } else {
            return ""
        }
    }
    
    private func fetchArguments() -> String {
        let defaults = UserDefaults.standard
        var a = defaults.string(forKey: defaultsArgumentsKey)
        
        if a == nil {
            a = "-L socks5://:1080"
        }
        
        return a!
    }
    
    private func pipe() -> Pipe {
        let p = Pipe()
        p.fileHandleForReading.readabilityHandler = { handle in
            let data = handle.availableData
            if data.count == 0 {
                // No data available means EOF; we must unregister ourselves
                // in order to not immediately be called again.
                handle.readabilityHandler = nil
                return
            }

            guard let str = String(data: data, encoding: .utf8) else {
                // Non-UTF-8 data from Syncthing should never happen.
                return
            }

            logger.log("\(str, privacy: .public)")
        }
        return p
    }
}
