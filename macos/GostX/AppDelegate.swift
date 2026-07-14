//
//  AppDelegate.swift
//  GostX
//
//  Created by  on 2022/6/23.
//

import SwiftUI
import Cocoa
import os
import Libgost

let logger = Logger(subsystem: Bundle.main.bundleIdentifier!, category: "runtime")

@objc protocol EditMenuActions {
    func redo(_ sender: AnyObject)
    func undo(_ sender: AnyObject)
}

class AppDelegate: NSObject, NSApplicationDelegate {
    private var menu: MacExtrasConfigurator?
    private var vpnMode: Bool {
        UserDefaults.standard.bool(forKey: defaultsVpnModeKey)
    }

    func mainMenu() {
        let mainMenu = NSMenu(title: "MainMenu")
        var menuItem = mainMenu.addItem(withTitle: "", action: nil, keyEquivalent: "")
        var submenu = NSMenu(title: "Application")
        mainMenu.setSubmenu(submenu, for: menuItem)

        menuItem = mainMenu.addItem(withTitle:"Edit", action:nil, keyEquivalent:"")
        submenu = NSMenu(title:NSLocalizedString("Edit", comment:"Edit menu"))
        submenu.addItem(withTitle: "Undo", action: #selector(EditMenuActions.undo(_:)), keyEquivalent: "z")
        submenu.addItem(withTitle: "Redo", action: #selector(EditMenuActions.redo(_:)), keyEquivalent: "Z")
        submenu.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        submenu.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        submenu.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        submenu.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        mainMenu.setSubmenu(submenu, for: menuItem)

        NSApp.mainMenu = mainMenu
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        self.mainMenu()
        self.menu = MacExtrasConfigurator(delegate: self)

        Task {
            await VpnManager.shared.setup()
        }

        // Auto-start based on saved state
        if UserDefaults.standard.bool(forKey: "last_vpn_running") {
            self.start()
        }
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
        if vpnMode {
            Task { @MainActor in VpnManager.shared.stop() }
        } else {
            LibgostStopGost(nil)
        }
        self.menu?.toOffState()
    }

    func start() {
        if vpnMode {
            startVpnMode()
        } else {
            startProxyMode()
        }
    }

    // MARK: - VPN Mode

    private func startVpnMode() {
        // 同步 YAML 到 App Group
        let yaml = UserDefaults.standard.string(forKey: defaultsYamlKey) ?? defaultGostYAML
        AppGroupConfig.writeYaml(yaml)

        Task {
            do {
                try await VpnManager.shared.start()
                self.menu?.toOnState()
            } catch {
                logger.error("VPN start failed: \(error.localizedDescription)")
                self.menu?.toOffState()
            }
        }
    }

    // MARK: - Proxy Mode (non-VPN)

    private func startProxyMode() {
        let yaml = UserDefaults.standard.string(forKey: defaultsYamlKey) ?? defaultGostYAML

        // Set work dir to App Group container so gost can find imported bypass files
        if let containerURL = AppGroupConfig.containerURL {
            let filesDir = containerURL.appendingPathComponent("files")
            try? FileManager.default.createDirectory(at: filesDir,
                withIntermediateDirectories: true, attributes: nil)
            LibgostSetWorkDir(filesDir.path, nil)
        }

        var err: NSError?
        if !LibgostStartGost(yaml, "", &err), let err {
            logger.error("gost start failed: \(err.localizedDescription)")
            self.menu?.toOffState()
            return
        }

        self.menu?.toOnState()
    }
}
