//
//  AppDelegate.swift
//  GostX
//
//  Created by 刘科彬 on 2022/6/23.
//

import SwiftUI
import Cocoa
import os
import Gost

let logger = Logger(subsystem: Bundle.main.bundleIdentifier!, category: "runtime")

class AppDelegate: NSObject, NSApplicationDelegate {
    private var menu: MacExtrasConfigurator?
    private var logPipe: Pipe?
    
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
    
    func applicationSupportsSecureRestorableState(_ app: NSApplication) -> Bool {
        return true
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
        let arguments = Arguments()
        arguments.refreshActive()
        
        let args: NSString = arguments.fetchActive().Value as NSString
        
        /* var fd: Int32? = 1 */ 
        var fd = self.logPipe?.fileHandleForWriting.fileDescriptor
        let fdPtr = UnsafeMutablePointer<CLong>.allocate(capacity: 1)
        withUnsafeMutablePointer(to: &fd, { (ptr: UnsafeMutablePointer<Int32?>) in
            fdPtr.initialize(to: CLong(ptr.pointee!))
        })

        let isFailed = gostRun(UnsafeMutablePointer<CChar>(mutating: args.utf8String),UnsafeMutablePointer<CLong>(mutating: fdPtr))
        if (isFailed != 0) {
            self.menu?.toOffState()
            // sometimes, if pass two or more same -L option value causes gost run faild,
            // but gost already started one service, so call stop to close that one
            stop()
            
            return
        }
        
        let i: UnsafeMutablePointer<info>? = gostInfo();
        self.menu?.updateListen(String(cString:i!.pointee.listen))
        self.menu?.toOnState()
        i?.deallocate()
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
