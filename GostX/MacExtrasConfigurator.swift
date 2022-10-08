//
//  MacextrasConfigurator.swift
//  GostX
//
//  Created by 刘科彬 on 2022/6/16.
//

import Foundation
import AppKit
import SwiftUI

let on = "🟢 On"
let off = "🔴 Off"

class MacExtrasConfigurator: NSObject {
    private var delegate: AppDelegate
    private var statusBar: NSStatusBar
    public var statusBarItem: NSStatusItem
    public var statusMenuItem: NSMenuItem
    public var statusActionOnItem: NSMenuItem
    public var statusActionOffItem: NSMenuItem
    public var statusActionRestartItem: NSMenuItem
    public var statusListenItem: NSMenuItem
    
    // MARK: - Lifecycle
    
    init(delegate: AppDelegate) {
        self.delegate = delegate
        self.statusBar = NSStatusBar.system
        self.statusBarItem = statusBar.statusItem(withLength: NSStatusItem.squareLength)
        
        self.statusMenuItem = NSMenuItem()
        self.statusActionOnItem = NSMenuItem()
        self.statusActionOffItem = NSMenuItem()
        self.statusActionRestartItem = NSMenuItem()
        self.statusListenItem = NSMenuItem()
        
        super.init()
        
        self.createMenu()
    }
    
    // MARK: - MenuConfig
    
    private func createMenu() {
        if let statusBarButton = statusBarItem.button {
            statusBarButton.image = NSImage(
                systemSymbolName: "network.badge.shield.half.filled",
                accessibilityDescription: nil
            )
            
            let mainMenu = NSMenu()
            
            statusMenuItem.title = on
            mainMenu.addItem(statusMenuItem)
            
            let statusSubMenu = NSMenu()
            statusSubMenu.autoenablesItems = false
            
            statusActionOnItem.title = "Start"
            statusActionOnItem.target = self
            statusActionOnItem.isEnabled = false
            statusActionOnItem.action = #selector(Self.onStartClick(_:))
            statusSubMenu.addItem(statusActionOnItem)
            
            statusActionOffItem.title = "Stop"
            statusActionOffItem.target = self
            statusActionOffItem.action = #selector(Self.onStopClick(_:))
            statusSubMenu.addItem(statusActionOffItem)
            
            statusActionRestartItem.title = "Restart"
            statusActionRestartItem.target = self
            statusActionRestartItem.action = #selector(Self.onRestartClick(_:))
            statusSubMenu.addItem(statusActionRestartItem)
            
            mainMenu.setSubmenu(statusSubMenu, for: statusMenuItem)
            
            statusListenItem.title = "✈️"
            statusListenItem.isEnabled = false;
            statusListenItem.isHidden = true;
            mainMenu.addItem(statusListenItem)
            
            mainMenu.addItem(.separator())
            
            let configMenuItem = NSMenuItem()
            configMenuItem.title = "Preferences..."
            configMenuItem.keyEquivalent = ","
            configMenuItem.keyEquivalentModifierMask = .command
            configMenuItem.target = self
            configMenuItem.action = #selector(Self.onConfigClick(_:))
            mainMenu.addItem(configMenuItem)
            
            mainMenu.addItem(.separator())
            
            let quitItem = NSMenuItem()
            quitItem.title = "Quit"
            quitItem.target = self
            quitItem.keyEquivalent = "q"
            quitItem.keyEquivalentModifierMask = .command
            quitItem.action = #selector(Self.onQuitClick(_:))
            mainMenu.addItem(quitItem)
            
            statusBarItem.menu = mainMenu
        }
    }
    
    // MARK: - Actions
    
    @objc private func onConfigClick(_ sender: Any?) {
        delegate.settings()
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
    
    public func toOffState() {
        self.statusMenuItem.title = off
        self.statusActionOnItem.isEnabled = true
        self.statusActionOffItem.isEnabled = false
        self.statusActionRestartItem.isEnabled = false
        self.statusBarItem.button?.image = NSImage(
            systemSymbolName: "network",
            accessibilityDescription: nil
        )
    }
    
    public func updateListen(_ listen: String?) {
        if listen == nil {
            statusListenItem.isHidden = true
            return
        }
        statusListenItem.isHidden = false
        statusListenItem.title = "✈️ \(listen!)"
    }
    
    public func toOnState() {
        self.statusMenuItem.title = on
        self.statusActionOnItem.isEnabled = false
        self.statusActionOffItem.isEnabled = true
        self.statusActionRestartItem.isEnabled = true
        self.statusBarItem.button?.image = NSImage(
            systemSymbolName: "network.badge.shield.half.filled",
            accessibilityDescription: nil
        )
    }
}
