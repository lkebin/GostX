# Fake IP

Fake IP lets the proxy do DNS resolution. Apps receive placeholder addresses
from a reserved range, and GostX remembers which domain each address belongs
to. When an app connects to a fake address, GostX replaces it with the domain
and sends the domain through the proxy, where it is resolved.

Use it when you want domain names, rather than client-resolved IPs, to reach
the proxy, and when bypass rules should match domains.

## Config

Fake IP is configured on a `dns` service, not on the `tungo` service. Add a
`fakeip://` node as the **last** forwarder node. Earlier nodes handle domains
you want resolved directly; a domain that bypasses them falls through to the
fakeip node.

```yaml
services:
  - name: vpn
    handler:
      type: tungo
      chain: upstream

  - name: dns
    addr: 127.0.0.1:1053
    handler:
      type: dns
      metadata:
        ttl: 30s
    listener:
      type: dns
      metadata:
        mode: udp
    forwarder:
      nodes:
        - name: domestic
          addr: udp://223.5.5.5:53
          bypasses:
            - proxy-domains
        - name: fakeip
          addr: "fakeip://?inet4=198.18.0.0/15&inet6=fc00::/18"

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

bypasses:
  - name: proxy-domains
    matchers:
      - "*.google.com"
      - "*.youtube.com"
```

Config notes:

- `bypasses` defaults to blacklist mode: a matching domain skips the
  `domestic` node and reaches `fakeip`.
- The fakeip node has no bypass, so it is the fallback for everything the
  earlier nodes skip.
- `inet4` defaults to `198.18.0.0/15` and `inet6` to `fc00::/18`. Both ranges
  must not overlap addresses you actually connect to.
- `handler.metadata.ttl` sets the TTL of fake answers and of the DNS cache.
- The first `dns` service in the config is used for the tunnel; `addr` can be
  any loopback port.

## How it works

1. The VPN advertises `10.0.0.3` as the system DNS server. Queries are captured
   inside the tunnel and sent to the configured DNS service, not through the
   chain.
2. The DNS handler picks the first forwarder node whose bypass does not match
   the domain. For proxied domains that is the `fakeip` node.
3. The fakeip node allocates an address (for example `198.18.0.1`), stores the
   `domain -> address` mapping, and answers immediately. It never contacts an
   upstream resolver.
4. The app connects to `198.18.0.1:443`. The TUN handler looks the address up,
   recovers the domain, and dials `domain:443` through the chain. The proxy
   resolves the real address; the client never needs it.
5. If the flow is routed direct, the domain is resolved locally instead.

The fake address never leaves the device.

## Persistence

Mappings are stored in `fakeip-state.json` in the app's files directory
(Android: external files directory; Apple: App Group container). The file is
loaded before services start and written after they stop, so DNS answers cached
by apps keep working across restarts.

- A missing file means an empty pool; the file is created on the next stop.
- A corrupt file, an unsupported version, or `inet4`/`inet6` ranges that differ
  from the config make startup fail. Keep the ranges stable, or clear app data
  to start over.
- Mappings created during a run are lost if the process is killed, because the
  file is only written on a normal stop.

## Limitations

- Apps that use their own DNS (DoH/DoT or a private resolver) never query the
  GostX DNS service, so they get real addresses and no fake IP is involved. For
  those flows, domain bypass matching falls back to sniffing the TLS SNI or the
  HTTP `Host` header.
- A connection to an address inside the fake range with no mapping is dialed
  as-is and usually fails.
- Fake IP is IPv4-oriented: AAAA answers use the IPv6 range when configured,
  but the tunnel only carries IPv4 app traffic, so apps fall back to IPv4.