# WireGuard upstream

Use a WireGuard peer as the upstream instead of a gost proxy server. The `wg`
dialer creates an in-process WireGuard tunnel, and the `wg` connector opens TCP
and UDP flows inside it.

You need the client private key and the server public key (base64), the server
`host:port`, and an address for the client inside the tunnel.

## Config

```yaml
bypasses:
  - name: wg-targets
    whitelist: true
    matchers:
      - 10.8.0.0/16
      - 192.168.9.13
      - "*.internal.example.com"

chains:
  - name: vpn-chain
    hops:
      - name: wg-hop
        bypass: wg-targets
        nodes:
          - name: wg-node
            dialer:
              type: wg
              metadata:
                privateKey: "<client private key, base64>"
                peerPublicKey: "<server public key, base64>"
                endpoint: wg.example.com:51820
                localIPs: 10.7.0.2/32
                allowedIPs: 0.0.0.0/0
                mtu: 1420
                keepalive: 25s
                # dns: 10.7.0.1
            connector:
              type: wg

services:
  - name: vpn
    handler:
      type: tungo
      chain: vpn-chain
```

## Split tunnel

A `bypass` on the hop with `whitelist: true` means: matched targets go through
the WireGuard peer, everything else skips the hop and is dialed directly. Add
the remote subnets you want reachable through WireGuard to the whitelist.

To send everything through WireGuard, remove `bypass: wg-targets` from the hop.
`allowedIPs` does not do the split: it is the peer's allowed-IPs list inside the
WireGuard tunnel and defaults to `0.0.0.0/0`.

## Dialer metadata

| Key | Required | Default | Description |
| --- | --- | --- | --- |
| `privateKey` | yes | — | Client private key, base64. |
| `peerPublicKey` | yes | — | Server public key, base64. |
| `endpoint` | yes | — | Server `host:port`; must be reachable over UDP. |
| `localIPs` | yes | — | Client address inside the tunnel, for example `10.7.0.2/32`. |
| `allowedIPs` | no | `0.0.0.0/0` | Peer allowed-IPs list inside the tunnel. |
| `dns` | no | — | Resolver inside the tunnel; needed only when targets arrive as domain names. |
| `mtu` | no | `1420` | Tunnel MTU (576–65535). |
| `keepalive` | no | `0` | Persistent keepalive, for example `25s` behind NAT. |

List-valued keys accept either a YAML list or a comma-separated string.

## Notes

- The `endpoint` hostname is resolved once when the tunnel is created; later
  server IP changes rely on WireGuard endpoint roaming.
- The tunnel's own UDP socket uses the physical interface and is protected from
  the VPN on Android, so it does not loop through the TUN.
- `localIPs` and `allowedIPs` accept IPv6, but GostX's TUN drops IPv6 app
  traffic, so IPv6 inside the tunnel is not used by apps.
- A destination that shares a subnet with your current network needs an
  explicit route on macOS/iOS (see [TUN mode](tungo.md#extra-routes-on-macos-and-ios)).
  Keep that route list in sync with the whitelist.