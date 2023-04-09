//
//  MacextrasConfigurator.swift
//  GostX
//
//  Created by 刘科彬 on 2022/6/16.
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
    public var argsItem: NSMenuItem = NSMenuItem()
    
    private var menu: NSMenu = NSMenu()
    private var activeImg: NSImage? = NSImage(named: "StatusBarActive")
    private var inActiveImg: NSImage? =  NSImage(named: "StatusBarInactive")
    
    init(delegate: AppDelegate) {
        self.delegate = delegate
        
        statusBar = NSStatusBar.system
        statusBarItem = statusBar.statusItem(withLength: NSStatusItem.squareLength)
        
        statusActionOnItem = NSMenuItem()
        statusActionOffItem = NSMenuItem()
        statusActionRestartItem = NSMenuItem()
        statusListenItem = NSMenuItem()
        
        super.init()
        
        createMenu()
    }
    
    // MARK: - MenuConfig
    
    private func createMenu() {
        menu.delegate = self
        menu.autoenablesItems = false
        
        // Listening
        statusListenItem.isEnabled = false
        menu.addItem(statusListenItem)
        
        menu.addItem(.separator())
        
        // Arguments
        argsItem.submenu = NSMenu()
        menu.addItem(argsItem)
        
        menu.addItem(.separator())
        
        // Actions
        statusActionOnItem.title = NSLocalizedString("Start", comment: "start the service")
        statusActionOnItem.target = self
        statusActionOnItem.isEnabled = false
        statusActionOnItem.action = #selector(onStartClick)
        menu.addItem(statusActionOnItem)
        
        statusActionOffItem.title = NSLocalizedString("Stop", comment: "stop the service")
        statusActionOffItem.target = self
        statusActionOffItem.isEnabled = false
        statusActionOffItem.action = #selector(onStopClick)
        menu.addItem(statusActionOffItem)
        
        statusActionRestartItem.title = NSLocalizedString("Restart", comment: "")
        statusActionRestartItem.target = self
        statusActionRestartItem.isEnabled = false
        statusActionRestartItem.action = #selector(onRestartClick)
        menu.addItem(statusActionRestartItem)
        
        menu.addItem(.separator())
        
        let configMenuItem = NSMenuItem()
        configMenuItem.title = NSLocalizedString("Preferences...", comment: "")
        configMenuItem.keyEquivalent = ","
        configMenuItem.keyEquivalentModifierMask = .command
        configMenuItem.target = self
        configMenuItem.action = #selector(onConfigClick)
        menu.addItem(configMenuItem)
        
        menu.addItem(.separator())
        
        let quitItem = NSMenuItem()
        quitItem.title = NSLocalizedString("Quit", comment: "")
        quitItem.target = self
        quitItem.keyEquivalent = "q"
        quitItem.keyEquivalentModifierMask = .command
        quitItem.action = #selector(onQuitClick)
        menu.addItem(quitItem)
        
        statusBarItem.menu = menu
    }
    
    // MARK: - Actions
    
    @objc private func onArgumentClick(_ sender: Any?) {
        Arguments().setActive(name:(sender! as! NSMenuItem).title)
        delegate.stop()
        delegate.start()
        updateArgsMenuItem()
    }
    
    @objc private func onConfigClick(_ sender: Any?) {
        NSApp.activate(ignoringOtherApps: true)
        NSApp.setActivationPolicy(.regular)
        
        if #available(macOS 13.0, *) {
            NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
        } else {
            NSApp.sendAction(Selector(("showPreferencesWindow:")), to: nil, from: nil)
        }
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
        updateArgsMenuItem()
    }
    
    private func updateArgsMenuItem() {
        let arguments = Arguments()
        let arg = arguments.fetchActive()
        argsItem.title = arg.Name
        argsItem.toolTip = arg.Value
        
        argsItem.submenu = NSMenu()
        
        // Arguments list
        for a in arguments.fetchList() {
            let i = NSMenuItem()
            i.title = a.Name
            i.toolTip = a.Value
            i.action = #selector(onArgumentClick)
            i.target = self
            i.state = argsItem.title == a.Name ? .on : .off
            argsItem.submenu?.addItem(i)
        }
    }
}
