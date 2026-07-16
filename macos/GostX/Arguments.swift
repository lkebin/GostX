// macos/GostX/Arguments.swift
import Foundation

// Storage key for the YAML configuration (gost v3 format).
let defaultsArgumentsKey = "gost_yaml_config"

let defaultGostYAML = """
services:
  - name: socks5-outbound
    addr: :1080
    handler:
      type: socks5
    listener:
      type: tcp
"""

// YAML 持久化 key（UserDefaults）
let defaultsYamlKey = "gost_yaml_config"

// VPN 模式 key
let defaultsVpnModeKey = "gost_vpn_mode_enabled"

// App Group 容器目录下 YAML 文件名
let appGroupYamlFileName = "gost.yaml"
