# TUN mode: the `tungo` handler

GostX has two run modes:

- **VPN mode** (Android, iOS, and macOS when the config contains `tungo`): all
  device traffic enters a TUN interface and is routed through one gost chain.
- **Proxy mode** (macOS only, config without `tungo`): gost runs as a normal
  local proxy server, for example a SOCKS5 listener on `:1080`.

Every VPN-mode config must contain exactly one service with
`handler.type: tungo`. The app removes that service before loading the rest of
the config and uses its `chain` for the TUN traffic. Android and iOS reject
configs without it.

## Minimal config

```yaml
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
              auth:
                username: chacha20-ietf-poly1305
                password: password
            dialer:
              type: tcp
```

## Handler fields

| Key | Required | Description |
| --- | --- | --- |
| `handler.type` | yes | Must be `tungo`. |
| `handler.chain` | yes | Chain every TUN flow is routed through. |
| `handler.metadata.routes` | no | Extra destinations to route into the tunnel (macOS/iOS only). |

`addr` and `listener` are not used by the `tungo` service; the TUN device is
created by the app.

## How it works

1. The app creates a TUN interface (`10.0.0.2/24`) and hands its file
   descriptor to `libgost`.
2. sing-tun's system stack rewrites IP/TCP headers and lets the OS kernel
   handle TCP reassembly, so gost receives ordinary TCP/UDP sessions.
3. Each session is routed through the chain named by `handler.chain`.
4. If the config contains a `dns` service, the VPN advertises `10.0.0.3` as the
   system resolver. Queries sent there are captured inside the tunnel and
   forwarded to that DNS service instead of going through the chain. Without a
   `dns` service, Android advertises `8.8.8.8` and DNS queries travel through
   the chain like any other flow. macOS and iOS always advertise `10.0.0.3`,
   so a VPN profile there should include a `dns` service.
5. Upstream sockets opened by gost bypass the VPN (Android
   `VpnService.protect`), so there is no routing loop.

Only IPv4 app traffic is carried: IPv6 destinations are dropped and apps fall
back to IPv4.

## Extra routes on macOS and iOS

The default route covers most cases. A destination that shares a subnet with
the network you are currently on is routed on-link by the OS and never enters
the tunnel. List those destinations in `handler.metadata.routes`:

```yaml
services:
  - name: vpn
    handler:
      type: tungo
      chain: upstream
      metadata:
        routes: 192.168.2.22/32, 172.16.5.0/24
```

A bare address is treated as a single host. Invalid entries are ignored and
logged. IPv6 entries are ignored. Android always routes `0.0.0.0/0` into the
tunnel, so this key only applies to macOS and iOS.

## Notes

- Per-app routing is a GostX setting, not part of the YAML.
- Keep `routes` in sync with any node bypass: the route decides what enters the
  tunnel, the bypass decides where the flow goes once inside.