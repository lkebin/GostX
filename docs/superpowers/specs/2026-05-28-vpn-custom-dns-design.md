# VPN Custom DNS via Gost Config

**Date**: 2026-05-28  
**Branch**: feature/android-client-gost-v3  
**Status**: Approved

## Problem

`GostVpnService.kt` hardcodes `.addDnsServer("8.8.8.8")`, preventing users from customising DNS resolution. Users may want encrypted DNS (DoH/DoT), custom upstream servers, or DNS queries routed through the proxy chain.

## Goal

Allow users to define a DNS service in their Gost YAML config. The VPN will automatically detect it and route all DNS queries through it instead of the hardcoded `8.8.8.8`.

## Design

### Virtual DNS IP approach

Inspired by sing-box: use **TUN address + 1** as a virtual DNS server IP.

- TUN interface address: `10.0.0.2/24`
- Virtual DNS IP: `10.0.0.3` (never bound by any process; purely a routing target)
- `addRoute("0.0.0.0", 0)` already captures all traffic including `10.0.0.3`, so DNS queries flow through TUN automatically.

Android apps send DNS queries to `10.0.0.3:53` → TUN fd → gVisor `HandleUDP`/`HandleTCP` → intercepted and forwarded to the actual Gost DNS service (e.g. `127.0.0.1:5353`).

### Data flow

```
App DNS query (to 10.0.0.3:53)
  → TUN fd
    → gVisor HandleUDP / HandleTCP
      → dst == vpnDNSVirtualIP:53 ?
        YES → net.Dial directly to 127.0.0.1:5353 (Gost DNS service, bypasses chain)
        NO  → router.Dial through proxy chain (normal traffic)
          → Gost DNS handler
            → optional: chain to upstream proxy
              → upstream DNS (e.g. 8.8.8.8)
```

Since the Gost process is excluded from VPN (`addDisallowedApplication(packageName)`), its outbound connections (including the DNS service's upstream queries) bypass the TUN directly and reach the proxy chain without a routing loop.

### Fallback

If no `handler.type: dns` service is present in the config, behaviour is unchanged: `addDnsServer("8.8.8.8")` and DNS queries route through the proxy chain to `8.8.8.8`.

---

## Changes

### `go/gostlib/gostlib.go`

1. **Register DNS handler and listener** (blank imports):
   ```go
   _ "github.com/go-gost/x/handler/dns"
   _ "github.com/go-gost/x/listener/dns"
   ```

2. **New global** `vpnDNSServiceAddr string` — stores the address of the user-configured DNS service (e.g. `"127.0.0.1:5353"`). Empty when no DNS service is configured.

3. **`StartVPNMode` — detect DNS service**:
   - Scan `cfg.Services` for the **first** service where `Handler.Type == "dns"`. If multiple are present, the first one wins; others start normally but are not used for VPN DNS.
   - Normalise the service address: if the host part is empty or `0.0.0.0` (e.g. `:5353`), substitute `127.0.0.1` so the dialer can reach it on loopback (e.g. `"127.0.0.1:5353"`). Store the normalised address in `vpnDNSServiceAddr` (under `mu`).
   - The DNS service is **not** removed from the config; it starts as a normal Gost service.
   - Unlike `tungo`, no extraction is needed because the DNS service runs as a standard listener.

4. **New exported function**:
   ```go
   // GetVPNDNSAddr returns the virtual DNS server IP to advertise to the Android
   // VPN if a DNS service is configured in the current Gost config, or "" if not.
   func GetVPNDNSAddr() string {
       mu.Lock()
       defer mu.Unlock()
       if vpnDNSServiceAddr == "" {
           return ""
       }
       return vpnDNSVirtualAddr
   }
   ```

### `go/gostlib/tun.go`

5. **New constants**:
   ```go
   const (
       vpnDNSVirtualAddr = "10.0.0.3"
       vpnDNSVirtualPort = 53
   )
   ```

6. **`gostTransportHandler` gets `dnsServiceAddr string` field**.  
   `startVPNGVisor` passes `vpnDNSServiceAddr` when constructing the handler.

7. **`HandleUDP` — DNS interception**:
   ```go
   if h.dnsServiceAddr != "" &&
       id.LocalAddress.String() == vpnDNSVirtualAddr &&
       int(id.LocalPort) == vpnDNSVirtualPort {
       upstream, err = net.Dial("udp", h.dnsServiceAddr)
   } else {
       upstream, err = h.router.Dial(ctx, "udp", dst)
   }
   ```

8. **`HandleTCP` — same interception** (for DNS over TCP, used when responses exceed 512 bytes):
   ```go
   if h.dnsServiceAddr != "" &&
       id.LocalAddress.String() == vpnDNSVirtualAddr &&
       int(id.LocalPort) == vpnDNSVirtualPort {
       upstream, err = net.Dial("tcp", h.dnsServiceAddr)
   } else {
       upstream, err = h.router.Dial(ctx, "tcp", dst)
   }
   ```

### `android/.../GostVpnService.kt`

9. **`GostLibBridge` — new method**:
   ```kotlin
   fun getVpnDnsAddr(): String = invoke("getVPNDNSAddr") as? String ?: ""
   ```

10. **`startVpn()` — dynamic DNS server**:
    ```kotlin
    val dnsAddr = GostLibBridge.getVpnDnsAddr()
    val builder = Builder()
        ...
        .addDnsServer(if (dnsAddr.isNotEmpty()) dnsAddr else "8.8.8.8")
        ...
    ```

---

## User Configuration Example

```yaml
services:
  - name: vpn
    addr: :0
    handler:
      type: tungo
      chain: upstream
    listener:
      type: tungo

  - name: dns-proxy           # VPN will automatically route DNS through this
    addr: 127.0.0.1:5353      # actual listen address (non-privileged port)
    handler:
      type: dns
      chain: upstream         # optional: DNS queries also go through the proxy
      metadata:
        dns: udp://8.8.8.8    # upstream DNS server
    listener:
      type: dns
      metadata:
        mode: udp             # UDP mode (standard DNS)

chains:
  - name: upstream
    hops:
      - name: hop0
        nodes:
          - name: server
            addr: your.proxy:port
            connector:
              type: ss
              metadata:
                method: chacha20-ietf-poly1305
                password: your-password
            dialer:
              type: tcp
```

**Without** the `dns-proxy` service block, the VPN falls back to `8.8.8.8` (current behaviour).

---

## Files Changed

| File | Change |
|------|--------|
| `go/gostlib/gostlib.go` | Add DNS imports, `vpnDNSServiceAddr`, DNS service detection in `StartVPNMode`, `GetVPNDNSAddr()` |
| `go/gostlib/tun.go` | Add `vpnDNSVirtualAddr` constant, `dnsServiceAddr` field on handler, DNS interception in `HandleUDP`/`HandleTCP` |
| `android/.../GostVpnService.kt` | Add `GostLibBridge.getVpnDnsAddr()`, use result in `addDnsServer()` |

---

## Non-goals

- Domain-based split DNS routing (can be layered on top later using Gost's hop/bypass config)
- Automatic DoH wrapping (user configures the DNS handler directly)
- IPv6 virtual DNS IP (out of scope for this change)
