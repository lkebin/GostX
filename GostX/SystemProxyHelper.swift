//
//  SystemProxyHelper.swift
//  GostX
//
//  Created by 刘科彬 on 2022/6/23.
//

import Foundation
import SystemConfiguration

class SystemProxyHelper: NSObject {
    private var authRef: AuthorizationRef?
    private var appDelegate: AppDelegate
    
    init(delegate: AppDelegate) {
        self.appDelegate = delegate
        AuthorizationCreate(nil, nil, [], &self.authRef)
        super.init()
    }
    
    func socksProxySet(enabled: Bool) {
        let rightName: String = "\(Bundle.main.bundleIdentifier!).rights"
        let authFlags: AuthorizationFlags = [.extendRights, .partialRights, .interactionAllowed, .preAuthorize]

        let authItem = UnsafeMutablePointer<AuthorizationItem>.allocate(capacity: 1)
        authItem.initialize(to: AuthorizationItem(name: (rightName as NSString).utf8String!, valueLength: 0, value:UnsafeMutableRawPointer(bitPattern: 0), flags: 0))
        
        var authRight = AuthorizationRights(count: 1, items: authItem)
        
        let osStatus = AuthorizationCreate(&authRight, nil, authFlags, &authRef)
        if (osStatus != errAuthorizationSuccess) {
            logger.error("authorization failed \(osStatus)")
            return
        }
        
        logger.log("Socks proxy set: \(enabled)")
        
        // set system proxy
        let prefRef = SCPreferencesCreateWithAuthorization(nil, "systemProxySet" as CFString, nil, self.authRef)!
        let sets = SCPreferencesGetValue(prefRef, kSCPrefNetworkServices)!
        
        var proxies = [NSObject: AnyObject]()
        
        // proxy enabled set
        if enabled {
            proxies[kCFNetworkProxiesSOCKSEnable] = 1 as NSNumber
            proxies[kCFNetworkProxiesSOCKSProxy] = "127.0.0.1" as AnyObject?
            proxies[kCFNetworkProxiesSOCKSPort] = 1080 as NSNumber
            proxies[kCFNetworkProxiesExcludeSimpleHostnames] = 1 as NSNumber
        } else {
            proxies[kCFNetworkProxiesSOCKSEnable] = 0 as NSNumber
        }
        
        sets.allKeys!.forEach { (key) in
            let dict = sets.object(forKey: key)!
            let hardware = (dict as AnyObject).value(forKeyPath: "Interface.Hardware")
            
            if hardware != nil && ["AirPort","Wi-Fi","Ethernet"].contains(hardware as! String) {
                SCPreferencesPathSetValue(prefRef, "/\(kSCPrefNetworkServices)/\(key)/\(kSCEntNetProxies)" as CFString, proxies as CFDictionary)
            }
        }
        
        // commit to system preferences.
        let commitRet = SCPreferencesCommitChanges(prefRef)
        let applyRet = SCPreferencesApplyChanges(prefRef)
        SCPreferencesSynchronize(prefRef)
        
        AuthorizationFree(self.authRef!, authFlags)
        
        logger.log("after SCPreferencesCommitChanges: commitRet = \(commitRet), applyRet = \(applyRet)")
    }
}
