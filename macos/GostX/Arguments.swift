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
