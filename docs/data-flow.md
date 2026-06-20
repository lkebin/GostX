## Android VPN Data Flow

When VPN mode is active, all device traffic is routed through a TUN virtual network interface. Here is the complete path for a request to an external server (e.g. `google.com`):

```mermaid
flowchart TD
    APP["App (e.g. Chrome)\nTCP connect → google.com:443"]
    TUN["TUN virtual interface\n(fd from VpnService)"]
    SINGTUN["sing-tun system stack\ngo/gostlib/tun.go\n\nparses IP packets → TCP/UDP sessions"]
    HANDLER["singTunHandler.NewConnectionEx()\ndst = 142.250.x.x:443"]
    GOST["gost chain router\ngithub.com/go-gost/x"]
    PROTECT["GlobalSocketControl(fd)\n→ VpnService.protect(fd)\n\nsocket created, not yet connected"]
    PROXY["Proxy server\n(SS / VMess / HTTPS CONNECT …)"]
    GOOGLE["google.com"]

    APP -->|"Android VPN routing\n0.0.0.0/0 → tun0"| TUN
    TUN --> SINGTUN
    SINGTUN --> HANDLER
    HANDLER --> GOST
    GOST -->|"create upstream socket"| PROTECT
    PROTECT -->|"socket bypasses tun0\ngoes via Wi-Fi / 5G directly"| PROXY
    PROXY -->|"proxy forwards request"| GOOGLE
```

### Why protect() is required

Without `protect()`, the upstream socket gost creates would also be caught by the VPN routing table and routed back into `tun0`, causing an infinite loop:

```mermaid
flowchart LR
    T1["tun0"] --> S1["sing-tun"]
    S1 --> G1["gost"]
    G1 -->|"new socket\n(no protect)"| T1
```

`VpnService.protect(fd)` tells Android to route that socket directly through the physical interface. It must be called **after socket creation but before `connect()`** — the only valid window. The hook is installed in gost's internal dialer so it fires automatically for every TCP and UDP upstream socket.

### DNS flow

```mermaid
flowchart LR
    APP2["App DNS query\n→ 10.0.0.3:53 (virtual)"]
    SINGTUN2["sing-tun\nNewPacketConnectionEx"]
    DNS["gost DNS service\n127.0.0.1:xxxx"]
    UPSTREAM["Upstream DNS\n(via proxy or direct)"]

    APP2 -->|"intercepted at sing-tun layer\nnot forwarded to proxy chain"| SINGTUN2
    SINGTUN2 -->|"redirected to loopback"| DNS
    DNS --> UPSTREAM
```
