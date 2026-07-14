//
//  MacextrasConfigurator.swift
//  GostX
//
//  Created by  on 2022/6/16.
//

import Foundation
import AppKit
import SwiftUI

class MacExtrasConfigurator: NSObject, NSMenuDelegate {
    private var delegate: AppDelegate
    private var statusBar: NSStatusBar
    public var statusBarItem: NSStatusItem
    public var statusActionOnItem: NSMenuItem
    public var statusActionOffItem: NSMenuItem
    public var statusActionRestartItem: NSMenuItem
    public var statusListenItem: NSMenuItem
    public var profileMenuItem: NSMenuItem = NSMenuItem()

    private var menu: NSMenu = NSMenu()
    private var activeImg: NSImage?
    private var inActiveImg: NSImage?
    private var settingsHostingController: NSHostingController<AnyView>?

    private var settingsWindow: NSWindow?

    init(delegate: AppDelegate) {
        self.delegate = delegate

        statusBar = NSStatusBar.system
        statusBarItem = statusBar.statusItem(withLength: NSStatusItem.squareLength)

        activeImg = NSImage(named: "StatusBarActive")
        activeImg?.isTemplate = true
        inActiveImg = NSImage(named: "StatusBarInactive")
        inActiveImg?.isTemplate = true
        statusBarItem.button?.image = inActiveImg

        statusActionOnItem = NSMenuItem()
        statusActionOffItem = NSMenuItem()
        statusActionRestartItem = NSMenuItem()
        statusListenItem = NSMenuItem()

        super.init()

        createMenu()
        toOffState()
    }

    // MARK: - MenuConfig

    private func createMenu() {
        menu.delegate = self
        menu.autoenablesItems = false

        // Listening status
        statusListenItem.isEnabled = false
        menu.addItem(statusListenItem)

        menu.addItem(.separator())

        // Actions
        statusActionOnItem.title = NSLocalizedString("Start", comment: "start the service")
        statusActionOnItem.image = NSImage(systemSymbolName: "play.fill", accessibilityDescription: "")
        statusActionOnItem.target = self
        statusActionOnItem.isEnabled = false
        statusActionOnItem.action = #selector(onStartClick)
        menu.addItem(statusActionOnItem)

        statusActionOffItem.title = NSLocalizedString("Stop", comment: "stop the service")
        statusActionOffItem.image = NSImage(systemSymbolName: "stop.fill", accessibilityDescription: "")
        statusActionOffItem.target = self
        statusActionOffItem.isEnabled = false
        statusActionOffItem.action = #selector(onStopClick)
        menu.addItem(statusActionOffItem)

        statusActionRestartItem.title = NSLocalizedString("Restart", comment: "")
        statusActionRestartItem.image = NSImage(systemSymbolName: "gobackward", accessibilityDescription: "")
        statusActionRestartItem.target = self
        statusActionRestartItem.isEnabled = false
        statusActionRestartItem.action = #selector(onRestartClick)
        menu.addItem(statusActionRestartItem)

        menu.addItem(.separator())

        // Configuration profiles submenu
        profileMenuItem.title = NSLocalizedString("Profile", comment: "")
        profileMenuItem.image = NSImage(systemSymbolName: "doc.text", accessibilityDescription: "")
        menu.addItem(profileMenuItem)

        let settingsItem = NSMenuItem()
        settingsItem.title = NSLocalizedString("Settings...", comment: "")
        settingsItem.image = NSImage(systemSymbolName: "gear", accessibilityDescription: "")
        settingsItem.keyEquivalent = ","
        settingsItem.keyEquivalentModifierMask = .command
        settingsItem.target = self
        settingsItem.action = #selector(onSettingsClick)
        menu.addItem(settingsItem)

        menu.addItem(.separator())

        let quitItem = NSMenuItem()
        quitItem.title = NSLocalizedString("Quit", comment: "")
        quitItem.image = NSImage(systemSymbolName: "power", accessibilityDescription: "")
        quitItem.target = self
        quitItem.keyEquivalent = "q"
        quitItem.keyEquivalentModifierMask = .command
        quitItem.action = #selector(onQuitClick)
        menu.addItem(quitItem)

        statusBarItem.menu = menu
    }

    // MARK: - Profile Submenu

    private func rebuildProfileSubmenu() {
        let repo = ConfigRepository.shared
        let submenu = NSMenu()
        submenu.autoenablesItems = false

        for profile in repo.profiles {
            let item = NSMenuItem()
            item.title = profile.name
            item.state = profile.id == repo.activeProfileId ? .on : .off
            item.target = self
            item.action = #selector(onSelectProfile(_:))
            item.representedObject = profile.id
            submenu.addItem(item)
        }

        if repo.profiles.isEmpty {
            let emptyItem = NSMenuItem()
            emptyItem.title = NSLocalizedString("No profiles", comment: "")
            emptyItem.isEnabled = false
            submenu.addItem(emptyItem)
        }

        submenu.addItem(.separator())

        let manageItem = NSMenuItem()
        manageItem.title = NSLocalizedString("Manage Profiles...", comment: "")
        manageItem.target = self
        manageItem.action = #selector(onSettingsClick)
        submenu.addItem(manageItem)

        profileMenuItem.submenu = submenu
    }

    @objc private func onSelectProfile(_ sender: NSMenuItem) {
        guard let profileId = sender.representedObject as? String else { return }
        ConfigRepository.shared.setActiveProfile(profileId)
    }

    // MARK: - Actions

    @objc private func onSettingsClick(_ sender: Any?) {
        guard #available(macOS 14.0, *) else { return }
        if let window = settingsWindow, window.isVisible {
            window.orderFrontRegardless()
            window.makeKey()
            return
        }
        settingsHostingController = NSHostingController(rootView: AnyView(SettingsView()))
        let window = NSWindow(contentViewController: settingsHostingController!)
        window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
        window.title = Bundle.main.object(forInfoDictionaryKey: "CFBundleName") as? String ?? "GostX"
        window.setContentSize(NSSize(width: 780, height: 500))
        window.center()
        window.orderFrontRegardless()
        window.makeKey()
        window.isReleasedWhenClosed = false
        settingsWindow = window
    }

    @objc private func onQuitClick(_ sender: Any?) {
        delegate.quit()
    }

    @objc private func onStopClick(_ sender: Any?) {
        delegate.stop()
    }

    @objc private func onStartClick(_ sender: Any?) {
        delegate.start()
    }

    @objc private func onRestartClick(_ sender: Any?) {
        delegate.stop()
        delegate.start()
    }

    public func updateListen(_ listen: String?) {
        if listen == nil {
            statusListenItem.isHidden = true
            return
        }
        statusListenItem.isHidden = false
        statusListenItem.attributedTitle = NSAttributedString(string: "\(listen!.replacingOccurrences(of: ";", with: "\n"))")
    }

    public func toOffState() {
        self.statusActionOnItem.isEnabled = true
        self.statusActionOffItem.isEnabled = false
        self.statusActionRestartItem.isEnabled = false
        self.statusBarItem.button?.image = inActiveImg
    }

    public func toOnState() {
        self.statusActionOnItem.isEnabled = false
        self.statusActionOffItem.isEnabled = true
        self.statusActionRestartItem.isEnabled = true
        self.statusBarItem.button?.image = activeImg
    }

    func menuWillOpen(_ menu: NSMenu) {
        rebuildProfileSubmenu()
    }
}
