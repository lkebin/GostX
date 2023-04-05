//
//  MacextrasConfigurator.swift
//  GostX
//
//  Created by 刘科彬 on 2022/6/16.
//

import Foundation
import AppKit
import SwiftUI

class MacExtrasConfigurator: NSObject {
    private var delegate: AppDelegate
    private var statusBar: NSStatusBar
    public var statusBarItem: NSStatusItem
    public var statusActionOnItem: NSMenuItem
    public var statusActionOffItem: NSMenuItem
    public var statusActionRestartItem: NSMenuItem
    public var statusListenItem: NSMenuItem
    
    private var menu: NSMenu = NSMenu()
    private var activeImg: NSImage? = NSImage(systemSymbolName: "network.badge.shield.half.filled", accessibilityDescription: nil)
    private var inActiveImg: NSImage? =  NSImage(systemSymbolName: "network.badge.shield.half.filled", accessibilityDescription: nil)?.withSymbolConfiguration(NSImage.SymbolConfiguration(hierarchicalColor:  NSColor.init(red: 220, green: 220, blue: 220, alpha: 80)))
    
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
        if let statusBarButton = statusBarItem.button {
            statusBarButton.image = activeImg
            
            menu.autoenablesItems = false
            
            // Listening
            statusListenItem.isEnabled = false
            menu.addItem(statusListenItem)
            
            menu.addItem(.separator())
            
            // Actions
            statusActionOnItem.title = NSLocalizedString("Start", comment: "start the service")
            statusActionOnItem.target = self
            statusActionOnItem.isEnabled = false
            statusActionOnItem.action = #selector(Self.onStartClick(_:))
            menu.addItem(statusActionOnItem)
            
            statusActionOffItem.title = NSLocalizedString("Stop", comment: "stop the service")
            statusActionOffItem.target = self
            statusActionOffItem.isEnabled = false
            statusActionOffItem.action = #selector(Self.onStopClick(_:))
            menu.addItem(statusActionOffItem)
            
            statusActionRestartItem.title = NSLocalizedString("Restart", comment: "")
            statusActionRestartItem.target = self
            statusActionRestartItem.isEnabled = false
            statusActionRestartItem.action = #selector(Self.onRestartClick(_:))
            menu.addItem(statusActionRestartItem)
            
            menu.addItem(.separator())
            
            let configMenuItem = NSMenuItem()
            configMenuItem.title = NSLocalizedString("Preferences...", comment: "")
            configMenuItem.keyEquivalent = ","
            configMenuItem.keyEquivalentModifierMask = .command
            configMenuItem.target = self
            configMenuItem.action = #selector(Self.onConfigClick(_:))
            menu.addItem(configMenuItem)
            
            menu.addItem(.separator())
            
            let quitItem = NSMenuItem()
            quitItem.title = NSLocalizedString("Quit", comment: "")
            quitItem.target = self
            quitItem.keyEquivalent = "q"
            quitItem.keyEquivalentModifierMask = .command
            quitItem.action = #selector(Self.onQuitClick(_:))
            menu.addItem(quitItem)
            
            statusBarItem.menu = menu
        }
    }
    
    // MARK: - Actions
    
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
}
