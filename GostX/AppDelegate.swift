//
//  AppDelegate.swift
//  GostX
//
//  Created by 刘科彬 on 2022/6/23.
//

import SwiftUI
import os
import Gost
import UserNotifications

let defaultsArgumentsKey = "arguments"
let logger = Logger(subsystem: Bundle.main.bundleIdentifier!, category: "runtime")

class AppDelegate: NSObject, NSApplicationDelegate {
    private var menu: MacExtrasConfigurator?
    private var executable: String = "\(Bundle.main.resourcePath!)/gost/gost"
    private var logPipe: Pipe?
    private var window: NSWindow?
    
    func applicationDidFinishLaunching(_ notification: Notification) {
        self.menu = MacExtrasConfigurator(delegate: self)
        self.logPipe = pipe()
        self.start()
    }
    
    func applicationWillTerminate(_ notification: Notification) {
        self.stop()
    }
    
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        NSApp.setActivationPolicy(.accessory)
        
        return false
    }
    
    func quit() {
        NSApp.terminate(self)
    }
    
    func stop() {
        gostStop()
        self.menu?.updateListen(nil)
        self.menu?.toOffState()
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
            self.menu?.toOffState()
            
            let n = UNMutableNotificationContent()
            n.title = "GostX service run failed!"
            n.subtitle = "Please check logs for details"
            n.sound = UNNotificationSound.default
            let request = UNNotificationRequest(identifier: UUID().uuidString, content: n, trigger: UNTimeIntervalNotificationTrigger(timeInterval: 5, repeats: false))
            UNUserNotificationCenter.current().add(request)
            
            return
        }
        
        let i: UnsafeMutablePointer<info>? = gostInfo();
        self.menu?.updateListen(String(cString:i!.pointee.listen))
        self.menu?.toOnState()
        free(i)
    }
    
    private func parseArguments(_ v: String) -> String {
        if !v.isEmpty {
            return v.components(separatedBy: CharacterSet.newlines).filter { e in
                return !e.isEmpty && !e.hasPrefix("#")
            }.map{ e in
                return e.trimmingCharacters(in: CharacterSet.whitespaces)
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
