//
//  Arguments.swift
//  GostX
//
//  Created by 刘科彬 on 2023/4/6.
//

import Foundation

let defaultsArgumentsKey = "arguments"
let defaultsArgumentsActiveKey = "arguments-active"

struct Argument {
    var Name: String
    var Value: String
}

class Arguments: NSObject {
    public func fetchList() -> [Argument] {
        return parse(raw())
    }
    
    public func fetchActive() -> Argument {
        let dict = UserDefaults.standard.dictionary(forKey: defaultsArgumentsActiveKey)
        if dict != nil {
            return Argument(Name: dict?["Name"] as! String, Value: dict?["Value"] as! String)
        }
        
        let list = fetchList()
        return list.first!
    }
    
    public func setActive(_ v: Argument) {
        UserDefaults.standard.set(["Name": v.Name, "Value": v.Value], forKey: defaultsArgumentsActiveKey)
    }
    
    public func setActive(name: String) {
        let lines = fetchList()
        for l in lines {
            if l.Name == name {
                UserDefaults.standard.set(["Name": l.Name, "Value": l.Value], forKey: defaultsArgumentsActiveKey)
                return
            }
        }
    }
    
    public func updateActive() {
        let lines = fetchList()
        let active = fetchActive()
        for l in lines {
            if l.Name == active.Name {
                UserDefaults.standard.set(["Name": l.Name, "Value": l.Value], forKey: defaultsArgumentsActiveKey)
                return
            }
        }
    }
    
    private func raw() -> String {
        let defaults = UserDefaults.standard
        var a = defaults.string(forKey: defaultsArgumentsKey)
        
        if a == nil || a == "" {
            a = """
# Default
-L socks5://:1080
"""
        }
        
        return a!
    }
    
    private func parse(_ v: String) -> [Argument] {
        if v.isEmpty {
            return []
        }
        
        let lines = v.components(separatedBy: CharacterSet.newlines)
        
        var args: [Argument] = []
        var name: String = ""
        var value: [String] = []
        var startWithHash: Bool = false
        
        for l in lines {
            let line = l.trimmingCharacters(in: .whitespaces)
            if line.isEmpty {
                continue
            }
            
            startWithHash = line.hasPrefix("#")
            
            if startWithHash && value.count > 0 {
                args.append(Argument(Name: name, Value: value.joined(separator: " ")))
                // reset
                name = ""
                value = []
            }
            
            if startWithHash {
                name = line.trimmingCharacters(in: .whitespaces.union(CharacterSet.init(charactersIn: "#")))
            } else {
                value.append(line)
            }
        }
        
        // append the last one
        if value.count > 0 {
            args.append(Argument(Name: name, Value: value.joined(separator: " ")))
        }
        
        return args
    }
}
