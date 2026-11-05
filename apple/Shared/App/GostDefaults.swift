import Foundation

/// Default profile YAML per platform.
///
/// macOS keeps the socks5 template used by its in-process proxy mode. iOS has
/// no proxy mode, so its default profile must be a tungo service or the first
/// connection attempt would be rejected.
enum GostDefaults {
    static var yaml: String {
        #if os(iOS)
        return vpnTemplate
        #else
        return proxyTemplate
        #endif
    }

    static let vpnTemplate = """
    services:
      - name: vpn
        handler:
          type: tungo
          chain: upstream

    chains:
      - name: upstream
        hops:
          - name: hop0
            nodes:
              - name: server
                addr: example.com
                connector:
                  type: ss
                  metadata:
                    method: chacha20-ietf-poly1305
                    password: password
                dialer:
                  type: tcp
    """

    static let proxyTemplate = """
    services:
      - name: socks5-outbound
        addr: :1080
        handler:
          type: socks5
        listener:
          type: tcp
    """
}
