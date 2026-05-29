# VPN Custom DNS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow users to define a Gost DNS service in their YAML config; the VPN will automatically route all DNS queries through it instead of hardcoding `8.8.8.8`.

**Architecture:** A virtual DNS IP (`10.0.0.3` = TUN address + 1) is advertised to Android via `addDnsServer()`. DNS queries for that IP pass through the TUN fd into the gVisor stack, where `HandleUDP`/`HandleTCP` detect destination `10.0.0.3:53` and dial the locally-running Gost DNS service directly, bypassing the proxy chain. When no DNS service is configured, the VPN falls back to `8.8.8.8` unchanged.

**Tech Stack:** Go 1.25, `github.com/go-gost/x` (handler/dns + listener/dns), gVisor (`gvisor.dev/gvisor`), Kotlin, Android VpnService API.

---

## File Map

| File | Role |
|------|------|
| `go/gostlib/gostlib.go` | Add DNS handler/listener imports; `vpnDNSServiceAddr` global; `vpnDNSVirtualAddr`/`vpnDNSVirtualPort` constants; `normalizeDNSAddr()`; `detectVPNDNSService()`; `GetVPNDNSAddr()` export; update `StartVPNMode` |
| `go/gostlib/tun.go` | Add `dnsServiceAddr` field to `gostTransportHandler`; update `StartVPN` to read `vpnDNSServiceAddr`; update `startVPNGVisor` signature; add DNS interception in `HandleTCP`/`HandleUDP` |
| `go/gostlib/gostlib_test.go` | Add `TestNormalizeDNSAddr`, `TestGetVPNDNSAddrNoService`, `TestGetVPNDNSAddrWithService`; extend `resetTestState` to clear `vpnDNSServiceAddr` |
| `android/app/src/main/kotlin/com/gostx/service/GostVpnService.kt` | Add `GostLibBridge.getVpnDnsAddr()`; use dynamic DNS addr in `startVpn()` |

---

## Task 1: Write failing tests for DNS detection

**Files:**
- Modify: `go/gostlib/gostlib_test.go`

- [ ] **Step 1: Extend `resetTestState` to clear new globals and add test YAML constants**

Open `go/gostlib/gostlib_test.go`. Add these constants after `testYAML` and update `resetTestState`:

```go
// DNS service listens on a fixed loopback port.  Port 15353 must be free on the test host.
const testDNSYAML = `
services:
  - name: test-socks5
    addr: 127.0.0.1:19080
    handler:
      type: socks5
    listener:
      type: tcp
  - name: test-dns
    addr: 127.0.0.1:15353
    handler:
      type: dns
      metadata:
        dns: udp://8.8.8.8
    listener:
      type: dns
      metadata:
        mode: udp`

// testDNSYAMLNoHost uses an unqualified address to exercise normalisation.
const testDNSYAMLNoHost = `
services:
  - name: test-dns
    addr: :15353
    handler:
      type: dns
      metadata:
        dns: udp://8.8.8.8
    listener:
      type: dns
      metadata:
        mode: udp`
```

Replace the body of `resetTestState` with:

```go
func resetTestState(t *testing.T) {
	t.Helper()
	_ = Stop()
	mu.Lock()
	services = nil
	running = false
	stopping = false
	cancelFn = nil
	vpnChainName = ""
	vpnDNSServiceAddr = ""
	mu.Unlock()
}
```

- [ ] **Step 2: Add three new test functions**

Append to `go/gostlib/gostlib_test.go`:

```go
func TestNormalizeDNSAddr(t *testing.T) {
	cases := []struct{ input, want string }{
		{":5353", "127.0.0.1:5353"},
		{"0.0.0.0:5353", "127.0.0.1:5353"},
		{"127.0.0.1:5353", "127.0.0.1:5353"},
		{"192.168.1.1:5353", "192.168.1.1:5353"},
	}
	for _, c := range cases {
		got := normalizeDNSAddr(c.input)
		if got != c.want {
			t.Errorf("normalizeDNSAddr(%q) = %q, want %q", c.input, got, c.want)
		}
	}
}

func TestGetVPNDNSAddrNoService(t *testing.T) {
	resetTestState(t)
	if err := StartVPNMode(testYAML); err != nil {
		t.Fatalf("StartVPNMode() failed: %v", err)
	}
	defer Stop()
	waitForRunning(t)

	if got := GetVPNDNSAddr(); got != "" {
		t.Fatalf("GetVPNDNSAddr() = %q, want empty when no DNS service", got)
	}
}

func TestGetVPNDNSAddrWithService(t *testing.T) {
	resetTestState(t)
	if err := StartVPNMode(testDNSYAML); err != nil {
		t.Fatalf("StartVPNMode() failed: %v", err)
	}
	defer Stop()
	waitForRunning(t)

	if got := GetVPNDNSAddr(); got != vpnDNSVirtualAddr {
		t.Fatalf("GetVPNDNSAddr() = %q, want %q", got, vpnDNSVirtualAddr)
	}
}

func TestGetVPNDNSAddrNormalisesHost(t *testing.T) {
	resetTestState(t)
	if err := StartVPNMode(testDNSYAMLNoHost); err != nil {
		t.Fatalf("StartVPNMode() failed: %v", err)
	}
	defer Stop()
	waitForRunning(t)

	// Service addr was ":15353"; vpnDNSServiceAddr must be "127.0.0.1:15353".
	mu.Lock()
	got := vpnDNSServiceAddr
	mu.Unlock()
	if got != "127.0.0.1:15353" {
		t.Fatalf("vpnDNSServiceAddr = %q, want %q", got, "127.0.0.1:15353")
	}
	// GetVPNDNSAddr must still return the virtual IP.
	if addr := GetVPNDNSAddr(); addr != vpnDNSVirtualAddr {
		t.Fatalf("GetVPNDNSAddr() = %q, want %q", addr, vpnDNSVirtualAddr)
	}
}
```

- [ ] **Step 3: Run tests to confirm they fail to compile (symbols not defined yet)**

```bash
cd /path/to/repo/go && go test ./gostlib/... 2>&1 | head -20
```

Expected: compilation errors mentioning `normalizeDNSAddr`, `GetVPNDNSAddr`, `vpnDNSVirtualAddr`, `vpnDNSServiceAddr` undefined.

---

## Task 2: Implement Go changes in `gostlib.go`

**Files:**
- Modify: `go/gostlib/gostlib.go`

- [ ] **Step 1: Add DNS plugin imports**

In `go/gostlib/gostlib.go`, add two blank imports inside the existing import block, after the last `_ "github.com/go-gost/x/handler/..."` line:

```go
	_ "github.com/go-gost/x/handler/dns"
```

And after the last `_ "github.com/go-gost/x/listener/..."` line:

```go
	_ "github.com/go-gost/x/listener/dns"
```

- [ ] **Step 2: Add constants and `vpnDNSServiceAddr` global**

After the existing `const VPNProxyAddr = "127.0.0.1:10808"` line add:

```go
// vpnDNSVirtualAddr is the virtual IP advertised to Android as the DNS server
// when a Gost DNS service is found in the config. It must fall within the VPN
// subnet (10.0.0.0/24) so that DNS queries are routed through the TUN fd.
// Value is TUN address (10.0.0.2) + 1.
const (
	vpnDNSVirtualAddr = "10.0.0.3"
	vpnDNSVirtualPort = 53
)
```

Inside the `var (...)` block (alongside `vpnChainName`), add:

```go
	// vpnDNSServiceAddr is the normalised listen address of the first DNS service
	// found in the config, e.g. "127.0.0.1:5353". Empty when none is configured.
	vpnDNSServiceAddr string
```

- [ ] **Step 3: Add helper functions `normalizeDNSAddr` and `detectVPNDNSService`**

Add these two functions anywhere before `StartVPNMode` in `go/gostlib/gostlib.go`:

```go
// normalizeDNSAddr replaces an empty or "0.0.0.0" host with "127.0.0.1" so
// that the gVisor handler can dial the DNS service on the loopback interface.
func normalizeDNSAddr(addr string) string {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return addr
	}
	if host == "" || host == "0.0.0.0" {
		host = "127.0.0.1"
	}
	return net.JoinHostPort(host, port)
}

// detectVPNDNSService returns the normalised address of the first service in
// cfg whose handler type is "dns", or "" if none is present.
func detectVPNDNSService(cfg *config.Config) string {
	for _, svc := range cfg.Services {
		if svc != nil && svc.Handler != nil && svc.Handler.Type == "dns" && svc.Addr != "" {
			return normalizeDNSAddr(svc.Addr)
		}
	}
	return ""
}
```

- [ ] **Step 4: Add `GetVPNDNSAddr` export**

Add after the existing `IsRunning` function:

```go
// GetVPNDNSAddr returns the virtual DNS server IP (vpnDNSVirtualAddr) to
// advertise to the Android VPN when a Gost DNS service has been detected in the
// active config, or "" when no DNS service is configured (fall back to 8.8.8.8).
func GetVPNDNSAddr() string {
	mu.Lock()
	defer mu.Unlock()
	if vpnDNSServiceAddr == "" {
		return ""
	}
	return vpnDNSVirtualAddr
}
```

- [ ] **Step 5: Update `StartVPNMode` to detect the DNS service**

Replace the existing block in `StartVPNMode` that sets `vpnChainName`:

```go
	mu.Lock()
	vpnChainName = chainName
	mu.Unlock()
```

with:

```go
	dnsServiceAddr := detectVPNDNSService(cfg)

	mu.Lock()
	vpnChainName = chainName
	vpnDNSServiceAddr = dnsServiceAddr
	mu.Unlock()
```

Note: `detectVPNDNSService` receives `cfg` (the original parsed config, before tungo extraction), which is correct because the DNS service is not extracted — it starts as a normal service.

Also add `"net"` to the import block in `gostlib.go` (needed by `normalizeDNSAddr`).

- [ ] **Step 6: Run tests — expect the new tests to pass**

```bash
cd /path/to/repo/go && go test ./gostlib/... -run 'TestNormalize|TestGetVPNDNS' -v
```

Expected output (all PASS):
```
--- PASS: TestNormalizeDNSAddr (0.00s)
--- PASS: TestGetVPNDNSAddrNoService (...)
--- PASS: TestGetVPNDNSAddrWithService (...)
--- PASS: TestGetVPNDNSAddrNormalisesHost (...)
```

- [ ] **Step 7: Run the full test suite to verify no regressions**

```bash
cd /path/to/repo/go && go test ./gostlib/... -v -count=1 2>&1 | tail -20
```

Expected: all existing tests still PASS.

- [ ] **Step 8: Commit**

```bash
cd /path/to/repo
git add go/gostlib/gostlib.go go/gostlib/gostlib_test.go
git commit -m "feat(gostlib): detect DNS service in VPN config and expose virtual DNS addr

- Register handler/dns and listener/dns plugins
- Add vpnDNSVirtualAddr constant (10.0.0.3) and vpnDNSServiceAddr global
- detectVPNDNSService() scans config for handler.type=dns; normalizeDNSAddr()
  substitutes 127.0.0.1 for empty/0.0.0.0 host
- GetVPNDNSAddr() returns virtual IP when DNS service is configured, else \"\"
- StartVPNMode stores detected DNS service address under mu alongside vpnChainName

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Task 3: Add DNS interception in `tun.go`

**Files:**
- Modify: `go/gostlib/tun.go`

- [ ] **Step 1: Add `dnsServiceAddr` field to `gostTransportHandler`**

Replace the existing struct definition:

```go
type gostTransportHandler struct {
	router *xchain.Router
}
```

with:

```go
type gostTransportHandler struct {
	router         *xchain.Router
	dnsServiceAddr string // loopback address of the Gost DNS service, e.g. "127.0.0.1:5353"
}
```

- [ ] **Step 2: Read `vpnDNSServiceAddr` in `StartVPN` and pass to `startVPNGVisor`**

Replace the existing block in `StartVPN` that reads `vpnChainName`:

```go
	mu.Lock()
	chainName := vpnChainName
	mu.Unlock()

	if chainName != "" {
		return startVPNGVisor(fd, mtu, chainName)
	}
```

with:

```go
	mu.Lock()
	chainName := vpnChainName
	dnsServiceAddr := vpnDNSServiceAddr
	mu.Unlock()

	if chainName != "" {
		return startVPNGVisor(fd, mtu, chainName, dnsServiceAddr)
	}
```

- [ ] **Step 3: Update `startVPNGVisor` signature and pass `dnsServiceAddr` to the handler**

Change the signature from:

```go
func startVPNGVisor(fd, mtu int, chainName string) error {
```

to:

```go
func startVPNGVisor(fd, mtu int, chainName, dnsServiceAddr string) error {
```

Inside `startVPNGVisor`, replace the `TransportHandler` field:

```go
		TransportHandler: &gostTransportHandler{router: router},
```

with:

```go
		TransportHandler: &gostTransportHandler{router: router, dnsServiceAddr: dnsServiceAddr},
```

- [ ] **Step 4: Add DNS interception to `HandleTCP`**

Replace the existing `HandleTCP` method body (the entire go func body) with:

```go
func (h *gostTransportHandler) HandleTCP(conn adapter.TCPConn) {
	go func() {
		defer conn.Close()

		id := conn.ID()
		dst := net.JoinHostPort(id.LocalAddress.String(), strconv.Itoa(int(id.LocalPort)))
		n := atomic.AddInt64(&vpnTCPConns, 1)
		logVPN("[tcp#%d] dial %s", n, dst)

		var upstream net.Conn
		var err error
		if h.dnsServiceAddr != "" &&
			id.LocalAddress.String() == vpnDNSVirtualAddr &&
			int(id.LocalPort) == vpnDNSVirtualPort {
			upstream, err = net.Dial("tcp", h.dnsServiceAddr)
		} else {
			upstream, err = h.router.Dial(context.Background(), "tcp", dst)
		}
		if err != nil {
			atomic.AddInt64(&vpnFailedConns, 1)
			logVPN("[tcp#%d] dial %s failed: %v", n, dst, err)
			return
		}
		defer upstream.Close()
		logVPN("[tcp#%d] relaying %s", n, dst)

		relay(conn, upstream)
		logVPN("[tcp#%d] done %s", n, dst)
	}()
}
```

- [ ] **Step 5: Add DNS interception to `HandleUDP`**

Replace the existing `HandleUDP` method body with:

```go
func (h *gostTransportHandler) HandleUDP(conn adapter.UDPConn) {
	go func() {
		defer conn.Close()

		id := conn.ID()
		dst := net.JoinHostPort(id.LocalAddress.String(), strconv.Itoa(int(id.LocalPort)))
		n := atomic.AddInt64(&vpnUDPConns, 1)
		logVPN("[udp#%d] dial %s", n, dst)

		var upstream net.Conn
		var err error
		if h.dnsServiceAddr != "" &&
			id.LocalAddress.String() == vpnDNSVirtualAddr &&
			int(id.LocalPort) == vpnDNSVirtualPort {
			upstream, err = net.Dial("udp", h.dnsServiceAddr)
		} else {
			upstream, err = h.router.Dial(context.Background(), "udp", dst)
		}
		if err != nil {
			atomic.AddInt64(&vpnFailedConns, 1)
			logVPN("[udp#%d] dial %s failed: %v", n, dst, err)
			return
		}
		defer upstream.Close()
		logVPN("[udp#%d] relaying %s", n, dst)

		relay(conn, upstream)
	}()
}
```

- [ ] **Step 6: Build (Linux/Android cross-compile check)**

Since the TUN file uses Linux-only syscalls, verify it compiles for the Android target:

```bash
cd /path/to/repo/go && GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build ./gostlib/ 2>&1
```

Expected: no output (clean build).

- [ ] **Step 7: Run full Go test suite one more time**

```bash
cd /path/to/repo/go && go test ./gostlib/... -count=1 2>&1 | tail -10
```

Expected: all PASS (tun_test.go tests still pass — `TestStopVPNWhenNotStarted` and `TestStartVPNInvalidFd`).

- [ ] **Step 8: Commit**

```bash
cd /path/to/repo
git add go/gostlib/tun.go
git commit -m "feat(gostlib/tun): intercept DNS packets in gVisor handler when DNS service configured

- Add dnsServiceAddr field to gostTransportHandler
- StartVPN reads vpnDNSServiceAddr under mu and passes to startVPNGVisor
- HandleTCP/HandleUDP: packets to 10.0.0.3:53 dial the local DNS service
  directly instead of routing through the proxy chain

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Task 4: Update Android `GostVpnService.kt`

**Files:**
- Modify: `android/app/src/main/kotlin/com/gostx/service/GostVpnService.kt`

- [ ] **Step 1: Add `getVpnDnsAddr()` to `GostLibBridge`**

Inside the `private object GostLibBridge` block, add after the existing `getVPNLog()` method:

```kotlin
fun getVpnDnsAddr(): String = invoke("getVPNDNSAddr") as? String ?: ""
```

- [ ] **Step 2: Use dynamic DNS address in `startVpn()`**

In `startVpn()`, find the existing lines:

```kotlin
        val builder = Builder()
            .setMtu(1500)
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
```

Replace `.addDnsServer("8.8.8.8")` with a dynamic value. Add the lookup just before the builder and update the builder line:

```kotlin
        val vpnDnsAddr = GostLibBridge.getVpnDnsAddr()
        val builder = Builder()
            .setMtu(1500)
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(if (vpnDnsAddr.isNotEmpty()) vpnDnsAddr else "8.8.8.8")
```

Note: `vpnDnsAddr` is `"10.0.0.3"` when the Gost config contains a DNS service (returned by `GetVPNDNSAddr()` in Go), or `""` when it doesn't (fall back to `"8.8.8.8"`).

- [ ] **Step 3: Run Android unit tests to verify no regression**

```bash
cd /path/to/repo/android && ./gradlew testDebugUnitTest 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL` — existing `GostVpnServicePolicyTest` tests still pass.

- [ ] **Step 4: Commit**

```bash
cd /path/to/repo
git add android/app/src/main/kotlin/com/gostx/service/GostVpnService.kt
git commit -m "feat(android): use Gost DNS service addr as VPN DNS when configured

GostLibBridge.getVpnDnsAddr() calls GetVPNDNSAddr() from gostlib.
startVpn() uses the returned virtual IP (10.0.0.3) if a DNS service
is configured, otherwise falls back to 8.8.8.8.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Task 5: Rebuild AAR and verify Android app compiles

**Files:**
- Artifact: `go/gostlib.aar` → copied to `android/app/libs/gostlib.aar`

- [ ] **Step 1: Rebuild the Android AAR with gomobile**

This requires `gomobile` to be installed and Android NDK configured. Run from the repo root:

```bash
cd /path/to/repo && make go/gostlib.aar
```

Or directly:

```bash
cd /path/to/repo/go && CGO_LDFLAGS="-Wl,-z,max-page-size=16384" GOFLAGS="-buildvcs=false" \
  gomobile bind \
    -target android/arm,android/arm64,android/amd64 \
    -androidapi 26 \
    -trimpath -ldflags="-s -w" \
    -o gostlib.aar \
    ./gostlib
```

Expected: `gostlib.aar` and `gostlib-sources.jar` produced; `gostlib.aar` automatically copied to `android/app/libs/`.

- [ ] **Step 2: Verify `GetVPNDNSAddr` is exported in the AAR Java API**

```bash
unzip -p /path/to/repo/go/gostlib-sources.jar gostlib/Gostlib.java | grep -A2 "getVPNDNSAddr"
```

Expected output:
```java
public static String getVPNDNSAddr() { ... }
```

- [ ] **Step 3: Build the Android debug APK**

```bash
cd /path/to/repo/android && ./gradlew assembleDebug 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit the updated AAR**

```bash
cd /path/to/repo
git add go/gostlib.aar go/gostlib-sources.jar android/app/libs/gostlib.aar
git commit -m "build: rebuild gostlib.aar with GetVPNDNSAddr export

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Self-Review Checklist

- [x] **Spec: DNS handler/listener imports** → Task 2 Step 1
- [x] **Spec: `vpnDNSServiceAddr` global** → Task 2 Step 2
- [x] **Spec: DNS detection in `StartVPNMode`** → Task 2 Steps 3–5
- [x] **Spec: Address normalisation (`:5353` → `127.0.0.1:5353`)** → Task 2 Step 3 (`normalizeDNSAddr`) + test in Task 1
- [x] **Spec: `GetVPNDNSAddr()` export** → Task 2 Step 4
- [x] **Spec: `dnsServiceAddr` on handler, passed from `StartVPN`** → Task 3 Steps 1–3
- [x] **Spec: HandleUDP DNS interception** → Task 3 Step 5
- [x] **Spec: HandleTCP DNS interception** → Task 3 Step 4
- [x] **Spec: Android `getVpnDnsAddr()` bridge method** → Task 4 Step 1
- [x] **Spec: `addDnsServer` uses dynamic value** → Task 4 Step 2
- [x] **Spec: Fallback to `8.8.8.8` when no DNS service** → Task 4 Step 2 (else branch) + TestGetVPNDNSAddrNoService
- [x] **AAR rebuild** → Task 5
