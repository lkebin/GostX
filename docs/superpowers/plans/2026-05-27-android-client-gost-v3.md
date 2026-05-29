# GostX Android Client & Gost v3 Upgrade — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an Android client with full VPN support and upgrade the shared Go library from gost v2 to v3, restructuring the repository for multi-platform support.

**Architecture:** A shared Go package (`go/gostlib/`) wraps gost v3 + tun2socks-go with a gomobile-compatible API. macOS compiles it as a c-archive (`libgost.a`); Android uses it as a gomobile `.aar`. Both platforms share YAML-based gost v3 configuration.

**Tech Stack:** Go 1.21+, go-gost/x (gost v3), xjasonlyu/tun2socks-go v2, gomobile; Kotlin + Jetpack Compose + Material 3 (Android); Swift + SwiftUI (macOS).

**Spec:** `docs/superpowers/specs/2026-05-27-android-client-gost-v3-design.md`

---

## File Map

### New Files
- `go/gostlib/gostlib.go` — gost v3 Start/Stop/Status API
- `go/gostlib/gostlib_test.go`
- `go/gostlib/tun.go` — tun2socks-go VPN layer
- `go/gostlib/tun_test.go`
- `go/cmd/macos/main.go` — cgo exports (replaces go/main.go)
- `go/cmd/macos/logger.go` — moved from go/logger.go
- `android/settings.gradle.kts`
- `android/build.gradle.kts`
- `android/gradle.properties`
- `android/.gitignore`
- `android/app/build.gradle.kts`
- `android/app/proguard-rules.pro`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/res/values/strings.xml`
- `android/app/src/main/res/values/themes.xml`
- `android/app/src/main/kotlin/com/gostx/MainActivity.kt`
- `android/app/src/main/kotlin/com/gostx/ui/Navigation.kt`
- `android/app/src/main/kotlin/com/gostx/ui/home/HomeScreen.kt`
- `android/app/src/main/kotlin/com/gostx/ui/home/HomeViewModel.kt`
- `android/app/src/main/kotlin/com/gostx/ui/log/LogScreen.kt`
- `android/app/src/main/kotlin/com/gostx/ui/config/ConfigScreen.kt`
- `android/app/src/main/kotlin/com/gostx/ui/config/ConfigViewModel.kt`
- `android/app/src/main/kotlin/com/gostx/ui/config/ConfigViewModelFactory.kt`
- `android/app/src/main/kotlin/com/gostx/data/ConfigRepository.kt`
- `android/app/src/main/kotlin/com/gostx/data/VpnStateRepository.kt`
- `android/app/src/main/kotlin/com/gostx/data/LogRepository.kt`
- `android/app/src/main/kotlin/com/gostx/service/GostVpnService.kt`
- `android/app/src/main/kotlin/com/gostx/tile/GostTileService.kt`
- `android/app/src/main/kotlin/com/gostx/notification/NotificationHelper.kt`
- `android/app/src/main/kotlin/com/gostx/receiver/BootReceiver.kt`
- `android/app/src/test/kotlin/com/gostx/ConfigRepositoryTest.kt`
- `android/app/src/test/kotlin/com/gostx/VpnStateRepositoryTest.kt`
- `android/app/src/androidTest/kotlin/com/gostx/HomeScreenTest.kt`
- `android/app/src/androidTest/kotlin/com/gostx/ConfigScreenTest.kt`

### Modified Files
- `go/go.mod` — remove gost v2 / Tor, add go-gost/x + tun2socks
- `go/Makefile` — new targets: libgost.a (macos), gostlib.aar (android)
- `macos/SettingsView.swift` — YAML editor (replaces args editor)
- `macos/Arguments.swift` — simplify to key constant only
- `macos/AppDelegate.swift` — call `gostRunYaml` instead of `gostRun`
- `.gitmodules` — remove gost v2 and bine entries
- `Makefile` — update paths for `macos/` and add `android` target

### Deleted Files
- `go/main.go`, `go/cfg.go`, `go/router.go`, `go/gost_server.go`, `go/peer.go`, `go/tor.go`
- `go/logger.go` (moved to `go/cmd/macos/logger.go`)
- `go/bine/` submodule, `go/gost/` submodule, `go/tor-static/`
- Built artifacts: `go/libgost.a`, `go/libgost.h`, `go/module.modulemap`

---

## Phase 1 — Repository Restructure

### Task 1: Move macOS files to `macos/`

**Files:**
- Move: `GostX/` → `macos/`
- Move: `GostX.xcodeproj` → `macos/GostX.xcodeproj`
- Move: `GostXTests/` → `macos/GostXTests/`
- Move: `GostXUITests/` → `macos/GostXUITests/`
- Modify: `Makefile`

- [ ] **Step 1.1: Move directories with git**

```bash
cd /path/to/GostX
git mv GostX macos
git mv GostXTests macos/GostXTests
git mv GostXUITests macos/GostXUITests
git mv GostX.xcodeproj macos/GostX.xcodeproj
git mv screenshots macos/screenshots
```

- [ ] **Step 1.2: Update root Makefile path (xcodeproj only — Tor removal comes in Task 2)**

```makefile
OTHER_LDFLAGS := -L./go/tor-static/tor -ltor \
                 -L./go/tor-static/zlib/dist/lib -lz \
                 -L./go/tor-static/libevent/dist/lib -levent \
                 -L./go/tor-static/openssl/dist/lib -lssl -lcrypto \
                 -L./go/tor-static/xz/dist/lib -llzma

.PHONY: all
all: debug

.PHONY: debug
debug: go/libgost.a
	xcodebuild OTHER_LDFLAGS="$(OTHER_LDFLAGS)" \
	  -scheme GostX -project macos/GostX.xcodeproj \
	  -configuration Debug -derivedDataPath ./build

.PHONY: release
release: go/libgost.a
	xcodebuild OTHER_LDFLAGS="$(OTHER_LDFLAGS)" \
	  -scheme GostX -project macos/GostX.xcodeproj \
	  -configuration Release -derivedDataPath ./build

go/libgost.a:
	cd go && $(MAKE)

.PHONY: clean
clean:
	cd go && $(MAKE) clean
	xcodebuild clean -project macos/GostX.xcodeproj -scheme GostX
```

- [ ] **Step 1.3: Verify Xcode project references**

The `macos/GostX.xcodeproj` links against `../go/libgost.a` and `../go/libgost.h`. Since `macos/` and `go/` are siblings, `../go/` is still valid. Open the project in Xcode and confirm there are no red (missing) file references.

- [ ] **Step 1.4: Commit**

```bash
git add -A
git commit -m "refactor: move macOS app to macos/ directory"
```

---

## Phase 2 — Go Library: gost v3

### Task 2: Remove gost v2, Tor, and old source files

**Files:**
- Delete submodules: `go/gost/`, `go/bine/`
- Delete: `go/main.go`, `go/cfg.go`, `go/router.go`, `go/gost_server.go`, `go/peer.go`, `go/tor.go`
- Modify: `go/go.mod`, `.gitmodules`
- Create dirs: `go/gostlib/`, `go/cmd/macos/`

- [ ] **Step 2.1: Remove submodules**

```bash
cd /path/to/GostX
git submodule deinit -f go/gost
git rm go/gost
git submodule deinit -f go/bine
git rm go/bine
rm -rf .git/modules/go/gost .git/modules/go/bine
```

- [ ] **Step 2.2: Remove old Go source files and Tor static libs**

```bash
git rm go/main.go go/cfg.go go/router.go go/gost_server.go go/peer.go go/tor.go
git rm -r go/tor-static
git rm go/libgost.a go/libgost.h go/module.modulemap 2>/dev/null || true
```

- [ ] **Step 2.3: Create new directory structure**

```bash
mkdir -p go/gostlib go/cmd/macos
```

- [ ] **Step 2.4: Update go/go.mod — add gost v3 + tun2socks, remove v2**

```
module libgost

go 1.21

require (
    github.com/go-gost/core v0.4.2
    github.com/go-gost/x v0.4.2
    github.com/xjasonlyu/tun2socks/v2 v2.5.2
    gopkg.in/yaml.v3 v3.0.1
)
```

Then fetch and tidy:

```bash
cd go
go get github.com/go-gost/core@latest
go get github.com/go-gost/x@latest
go get github.com/xjasonlyu/tun2socks/v2@latest
go mod tidy
```

- [ ] **Step 2.5: Commit**

```bash
git add -A
git commit -m "refactor: remove gost v2, bine, and Tor; add gost v3 and tun2socks deps"
```

---

### Task 3: Create `go/gostlib/gostlib.go`

**Files:**
- Create: `go/gostlib/gostlib.go`
- Create: `go/gostlib/gostlib_test.go`

- [ ] **Step 3.1: Write failing test**

```go
// go/gostlib/gostlib_test.go
package gostlib

import (
    "encoding/json"
    "testing"
    "time"
)

const testYAML = `
services:
  - name: test-socks5
    addr: 127.0.0.1:19080
    handler:
      type: socks5
    listener:
      type: tcp`

func TestStartStop(t *testing.T) {
    if err := Start(testYAML); err != nil {
        t.Fatalf("Start() failed: %v", err)
    }
    time.Sleep(100 * time.Millisecond)

    if !IsRunning() {
        t.Fatal("IsRunning() should be true after Start()")
    }

    if err := Stop(); err != nil {
        t.Fatalf("Stop() failed: %v", err)
    }

    if IsRunning() {
        t.Fatal("IsRunning() should be false after Stop()")
    }
}

func TestDoubleStart(t *testing.T) {
    _ = Start(testYAML)
    defer Stop()

    err := Start(testYAML)
    if err == nil {
        t.Fatal("second Start() should return an error")
    }
}

func TestGetStatus(t *testing.T) {
    if err := Start(testYAML); err != nil {
        t.Fatalf("Start() failed: %v", err)
    }
    defer Stop()
    time.Sleep(100 * time.Millisecond)

    raw := GetStatus()
    var info map[string]interface{}
    if err := json.Unmarshal([]byte(raw), &info); err != nil {
        t.Fatalf("GetStatus() returned invalid JSON: %v — got: %s", err, raw)
    }
    if running, ok := info["running"].(bool); !ok || !running {
        t.Fatalf("expected running=true in status, got: %s", raw)
    }
}

func TestInvalidYAML(t *testing.T) {
    err := Start("not: valid: yaml: [[")
    if err == nil {
        t.Fatal("Start() should return error for invalid YAML")
    }
}

func TestStartVPNModeInjectsService(t *testing.T) {
    if err := StartVPNMode(testYAML); err != nil {
        t.Fatalf("StartVPNMode() failed: %v", err)
    }
    defer Stop()
    time.Sleep(100 * time.Millisecond)

    if !IsRunning() {
        t.Fatal("IsRunning() should be true after StartVPNMode()")
    }
}
```

- [ ] **Step 3.2: Run test to verify it fails**

```bash
cd go && go test ./gostlib/... -v -run TestStartStop
```

Expected: FAIL — "cannot find package" or "undefined: Start"

- [ ] **Step 3.3: Create gostlib.go**

```go
// go/gostlib/gostlib.go
package gostlib

import (
    "context"
    "encoding/json"
    "fmt"
    "sync"

    "github.com/go-gost/core/service"
    "github.com/go-gost/x/config"
    "github.com/go-gost/x/config/parsing"

    // Register all built-in handlers, listeners, connectors, dialers
    _ "github.com/go-gost/x/connector/http"
    _ "github.com/go-gost/x/connector/socks/v4"
    _ "github.com/go-gost/x/connector/socks/v5"
    _ "github.com/go-gost/x/connector/ss"
    _ "github.com/go-gost/x/dialer/tcp"
    _ "github.com/go-gost/x/dialer/tls"
    _ "github.com/go-gost/x/dialer/udp"
    _ "github.com/go-gost/x/dialer/ws"
    _ "github.com/go-gost/x/handler/forward/local"
    _ "github.com/go-gost/x/handler/forward/remote"
    _ "github.com/go-gost/x/handler/http"
    _ "github.com/go-gost/x/handler/redirect"
    _ "github.com/go-gost/x/handler/relay"
    _ "github.com/go-gost/x/handler/socks/v4"
    _ "github.com/go-gost/x/handler/socks/v5"
    _ "github.com/go-gost/x/handler/ss"
    _ "github.com/go-gost/x/listener/tcp"
    _ "github.com/go-gost/x/listener/tls"
    _ "github.com/go-gost/x/listener/udp"
    _ "github.com/go-gost/x/listener/ws"

    "gopkg.in/yaml.v3"
)

var (
    mu       sync.Mutex
    running  bool
    services []service.Service
    cancelFn context.CancelFunc
)

// Start starts gost v3 with the given YAML configuration string.
func Start(yamlConfig string) error {
    mu.Lock()
    defer mu.Unlock()

    if running {
        return fmt.Errorf("gost is already running; call Stop() first")
    }

    cfg := &config.Config{}
    if err := yaml.Unmarshal([]byte(yamlConfig), cfg); err != nil {
        return fmt.Errorf("invalid YAML config: %w", err)
    }

    ctx, cancel := context.WithCancel(context.Background())
    cancelFn = cancel

    svcs, err := startServices(ctx, cfg)
    if err != nil {
        cancel()
        return err
    }

    services = svcs
    running = true
    return nil
}

// StartVPNMode starts gost with an auto-injected internal SOCKS5 at 127.0.0.1:10808
// for use by tun2socks. Call StartVPN(fd, mtu) after this returns nil.
func StartVPNMode(yamlConfig string) error {
    cfg := &config.Config{}
    if err := yaml.Unmarshal([]byte(yamlConfig), cfg); err != nil {
        return fmt.Errorf("invalid YAML config: %w", err)
    }
    injectInternalSocks5(cfg)

    b, err := yaml.Marshal(cfg)
    if err != nil {
        return err
    }
    return Start(string(b))
}

// Stop stops all running gost services.
func Stop() error {
    mu.Lock()
    defer mu.Unlock()

    if !running {
        return nil
    }
    if cancelFn != nil {
        cancelFn()
        cancelFn = nil
    }
    for _, svc := range services {
        svc.Close()
    }
    services = nil
    running = false
    return nil
}

// IsRunning returns true if gost is currently running.
func IsRunning() bool {
    mu.Lock()
    defer mu.Unlock()
    return running
}

// GetStatus returns a JSON string: {"running": bool, "addresses": ["host:port", ...]}.
func GetStatus() string {
    mu.Lock()
    defer mu.Unlock()

    addrs := make([]string, 0, len(services))
    for _, svc := range services {
        if addr := svc.Addr(); addr != nil {
            addrs = append(addrs, addr.String())
        }
    }
    b, _ := json.Marshal(map[string]interface{}{
        "running":   running,
        "addresses": addrs,
    })
    return string(b)
}

func startServices(ctx context.Context, cfg *config.Config) ([]service.Service, error) {
    svcs := make([]service.Service, 0, len(cfg.Services))
    for _, svcCfg := range cfg.Services {
        svc, err := parsing.ParseService(svcCfg)
        if err != nil {
            for _, s := range svcs {
                s.Close()
            }
            return nil, fmt.Errorf("failed to parse service %q: %w", svcCfg.Name, err)
        }
        svcs = append(svcs, svc)
        go svc.Serve()
    }
    return svcs, nil
}

func injectInternalSocks5(cfg *config.Config) {
    const internalAddr = "127.0.0.1:10808"
    for _, svc := range cfg.Services {
        if svc.Addr == internalAddr {
            return // already declared by user
        }
    }
    cfg.Services = append(cfg.Services, &config.ServiceConfig{
        Name:     "_gostx_vpn_internal",
        Addr:     internalAddr,
        Handler:  &config.HandlerConfig{Type: "socks5"},
        Listener: &config.ListenerConfig{Type: "tcp"},
    })
}
```

- [ ] **Step 3.4: Run all gostlib tests**

```bash
cd go && go test ./gostlib/... -v
```

Expected: All 5 tests PASS

- [ ] **Step 3.5: Commit**

```bash
git add go/gostlib/gostlib.go go/gostlib/gostlib_test.go go/go.mod go/go.sum
git commit -m "feat: add gostlib core package with gost v3 integration"
```

---

### Task 4: Create `go/gostlib/tun.go`

**Files:**
- Create: `go/gostlib/tun.go`
- Create: `go/gostlib/tun_test.go`

- [ ] **Step 4.1: Write failing test**

```go
// go/gostlib/tun_test.go
package gostlib

import "testing"

func TestStopVPNWhenNotStarted(t *testing.T) {
    // StopVPN on an idle instance should be a no-op
    if err := StopVPN(); err != nil {
        t.Fatalf("StopVPN() on idle should not error: %v", err)
    }
}

func TestStartVPNInvalidFd(t *testing.T) {
    // fd=-1 must return an error without panicking
    if err := StartVPN(-1, 1500); err == nil {
        t.Fatal("StartVPN(-1, 1500) should return an error")
    }
}
```

- [ ] **Step 4.2: Run test to verify it fails**

```bash
cd go && go test ./gostlib/... -v -run TestStopVPNWhenNotStarted
```

Expected: FAIL — "undefined: StopVPN"

- [ ] **Step 4.3: Create tun.go**

```go
// go/gostlib/tun.go
package gostlib

import (
    "fmt"
    "sync"

    "github.com/xjasonlyu/tun2socks/v2/engine"
)

var (
    tunMu      sync.Mutex
    tunRunning bool
)

const internalProxyAddr = "socks5://127.0.0.1:10808"

// StartVPN starts tun2socks, routing all device traffic through gost's
// internal SOCKS5 listener. fd is the TUN file descriptor obtained from
// Android VpnService.Builder.establish() or iOS NEPacketTunnelProvider.
// Call StartVPNMode() successfully before calling StartVPN().
func StartVPN(fd int, mtu int) error {
    tunMu.Lock()
    defer tunMu.Unlock()

    if tunRunning {
        return fmt.Errorf("tun2socks already running; call StopVPN() first")
    }
    if fd < 0 {
        return fmt.Errorf("invalid TUN file descriptor: %d", fd)
    }

    key := &engine.Key{
        Device:   fmt.Sprintf("fd://%d", fd),
        Proxy:    internalProxyAddr,
        LogLevel: "error",
        MTU:      mtu,
    }

    engine.Insert(key)
    if err := engine.Start(); err != nil {
        return fmt.Errorf("tun2socks failed to start: %w", err)
    }

    tunRunning = true
    return nil
}

// StopVPN stops tun2socks. Safe to call when not running.
func StopVPN() error {
    tunMu.Lock()
    defer tunMu.Unlock()

    if !tunRunning {
        return nil
    }

    engine.Stop()
    tunRunning = false
    return nil
}
```

- [ ] **Step 4.4: Run tun tests**

```bash
cd go && go test ./gostlib/... -v -run "TestStopVPNWhenNotStarted|TestStartVPNInvalidFd"
```

Expected: Both PASS

- [ ] **Step 4.5: Commit**

```bash
git add go/gostlib/tun.go go/gostlib/tun_test.go
git commit -m "feat: add tun2socks VPN layer to gostlib"
```

---

### Task 5: Create `go/cmd/macos/main.go` (cgo exports)

**Files:**
- Create: `go/cmd/macos/main.go`
- Create: `go/cmd/macos/logger.go` (moved from `go/logger.go`)

- [ ] **Step 5.1: Move and clean logger.go**

```bash
git mv go/logger.go go/cmd/macos/logger.go
```

Open `go/cmd/macos/logger.go` and replace its entire content (remove all gost v2 imports, keep only the io.Writer-based logger):

```go
// go/cmd/macos/logger.go
package main

import (
    "fmt"
    "io"
    "time"
)

type Logger struct {
    w io.Writer
}

func NewLogger(w io.Writer) *Logger {
    return &Logger{w: w}
}

func (l *Logger) Log(v ...interface{}) {
    fmt.Fprintf(l.w, "[%s] %s\n", time.Now().Format("15:04:05"), fmt.Sprint(v...))
}

func (l *Logger) Logf(format string, v ...interface{}) {
    fmt.Fprintf(l.w, "[%s] %s\n", time.Now().Format("15:04:05"), fmt.Sprintf(format, v...))
}
```

- [ ] **Step 5.2: Create go/cmd/macos/main.go**

```go
// go/cmd/macos/main.go
package main

/*
#include <stdlib.h>
struct gost_info {
    char* status_json;
};
*/
import "C"

import (
    "os"
    "unsafe"

    "libgost/gostlib"
)

//export gostRunYaml
func gostRunYaml(yaml *C.char, fd *C.long) C.int {
    // The fd is kept for future log-pipe use; gost v3 logs internally.
    _ = os.NewFile(uintptr(*fd), "logpipe")

    if err := gostlib.Start(C.GoString(yaml)); err != nil {
        return 1
    }
    return 0
}

//export gostStop
func gostStop() C.int {
    gostlib.Stop()
    return 0
}

//export gostInfo
func gostInfo() *C.struct_gost_info {
    info := (*C.struct_gost_info)(C.malloc(C.size_t(unsafe.Sizeof(C.struct_gost_info{}))))
    info.status_json = C.CString(gostlib.GetStatus())
    return info
}

func main() {}
```

- [ ] **Step 5.3: Verify compilation (host arch)**

```bash
cd go && CGO_ENABLED=1 go build ./cmd/macos/
```

Expected: exits 0, no output.

- [ ] **Step 5.4: Commit**

```bash
git add go/cmd/macos/
git commit -m "feat: add macOS cgo entry point wrapping gostlib"
```

---

### Task 6: Update `go/Makefile` and root `Makefile`

**Files:**
- Rewrite: `go/Makefile`
- Rewrite: `Makefile`

- [ ] **Step 6.1: Rewrite go/Makefile**

```makefile
GO       := go
GOMOBILE := gomobile

.PHONY: all libgost.a gostlib.aar clean

all: libgost.a

# macOS universal binary (arm64 + amd64)
libgost.a: libgost_arm64.a libgost_amd64.a
	lipo libgost_arm64.a libgost_amd64.a -create -output libgost.a
	cp libgost_arm64.h libgost.h
	rm libgost_arm64.a libgost_arm64.h libgost_amd64.a libgost_amd64.h

libgost_arm64.a:
	CGO_ENABLED=1 GOARCH=arm64 $(GO) build \
	  -buildmode=c-archive -trimpath --ldflags="-s -w" \
	  -o libgost_arm64.a ./cmd/macos

libgost_amd64.a:
	CGO_ENABLED=1 GOARCH=amd64 $(GO) build \
	  -buildmode=c-archive -trimpath --ldflags="-s -w" \
	  -o libgost_amd64.a ./cmd/macos

# Android .aar via gomobile bind (targets ./gostlib directly)
gostlib.aar:
	$(GOMOBILE) bind \
	  -target android/arm,android/arm64,android/amd64 \
	  -androidapi 26 \
	  -trimpath -ldflags="-s -w" \
	  -o gostlib.aar \
	  ./gostlib
	cp gostlib.aar ../android/app/libs/gostlib.aar

.PHONY: clean
clean:
	rm -f libgost.a libgost.h libgost_arm64.* libgost_amd64.* \
	      gostlib.aar gostlib-sources.jar
```

- [ ] **Step 6.2: Rewrite root Makefile**

```makefile
.PHONY: all macos android clean

all: macos

macos: go/libgost.a
	xcodebuild \
	  -scheme GostX -project macos/GostX.xcodeproj \
	  -configuration Debug -derivedDataPath ./build

android: go/gostlib.aar
	cd android && ./gradlew assembleDebug

go/libgost.a:
	cd go && $(MAKE) libgost.a

go/gostlib.aar:
	cd go && $(MAKE) gostlib.aar

.PHONY: clean
clean:
	cd go && $(MAKE) clean
	xcodebuild clean -project macos/GostX.xcodeproj -scheme GostX 2>/dev/null || true
	cd android && ./gradlew clean 2>/dev/null || true
```

- [ ] **Step 6.3: Build macOS Go library**

```bash
cd go && make libgost.a 2>&1 | tail -5
```

Expected: Creates `go/libgost.a` and `go/libgost.h`.

```bash
ls -lh go/libgost.a go/libgost.h
```

Expected: Both files present, `libgost.a` is several MB.

- [ ] **Step 6.4: Verify libgost.h contains new symbols**

```bash
grep -E "gostRunYaml|gostStop|gostInfo" go/libgost.h
```

Expected: All three symbols present.

- [ ] **Step 6.5: Commit**

```bash
git add go/Makefile Makefile
git commit -m "build: update Makefiles for gost v3 and Android targets"
```

---

## Phase 3 — macOS App Update

### Task 7: Update `SettingsView.swift` to YAML editor

**Files:**
- Modify: `macos/SettingsView.swift`
- Modify: `macos/Arguments.swift`

- [ ] **Step 7.1: Simplify Arguments.swift**

Replace entire file content:

```swift
// macos/Arguments.swift
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
```

- [ ] **Step 7.2: Replace SettingsView.swift**

```swift
// macos/SettingsView.swift
import SwiftUI
import HighlightedTextEditor

let reOpts = NSRegularExpression.Options([.anchorsMatchLines])
let yamlKeyRule   = try! NSRegularExpression(
    pattern: "^(\\s*)(services|chains|hops|name|addr|handler|listener|connector|dialer|type|chain|auth|tls|metadata|bypass|resolver|hosts|retries|timeout)\\s*:",
    options: reOpts)
let yamlCommentRule = try! NSRegularExpression(pattern: "^\\s*#.*", options: reOpts)

struct SettingsView: View {
    var body: some View {
        TabView {
            YamlConfigView()
                .tabItem {
                    Label(NSLocalizedString("Configuration", comment: ""), systemImage: "doc.text")
                }
                .tag("config")
        }
        .padding(5)
    }
}

struct YamlConfigView: View {
    @AppStorage(defaultsArgumentsKey)
    private var yamlConfig = defaultGostYAML

    private let rules: [HighlightRule] = [
        HighlightRule(
            pattern: yamlCommentRule,
            formattingRule: TextFormattingRule(key: .foregroundColor, value: NSColor.systemGray)
        ),
        HighlightRule(
            pattern: yamlKeyRule,
            formattingRules: [
                TextFormattingRule(fontTraits: .bold),
                TextFormattingRule(key: .foregroundColor, value: NSColor.systemBlue),
            ]
        ),
    ]

    var body: some View {
        VStack {
            HighlightedTextEditor(text: $yamlConfig, highlightRules: rules)
                .introspect { editor in
                    editor.textView.allowsUndo = true
                    editor.textView.breakUndoCoalescing()
                    editor.textView.font = NSFont.monospacedSystemFont(ofSize: 12, weight: .regular)
                }
            Text("gost v3 YAML configuration — https://gost.run/docs/")
                .padding(.horizontal, 5)
                .font(Font.system(size: 12))
                .foregroundColor(.gray)
        }
    }
}

struct SettingsView_Previews: PreviewProvider {
    static var previews: some View {
        SettingsView()
    }
}
```

- [ ] **Step 7.3: Commit**

```bash
git add macos/SettingsView.swift macos/Arguments.swift
git commit -m "feat(macos): replace args editor with gost v3 YAML editor"
```

---

### Task 8: Update `AppDelegate.swift` to use `gostRunYaml`

**Files:**
- Modify: `macos/AppDelegate.swift`
- Modify: `macos/module.modulemap` (if it references old `info` struct field)

- [ ] **Step 8.1: Verify libgost.h struct field name**

```bash
grep -A3 "struct gost_info" go/libgost.h
```

Ensure the field is named `status_json` (matching `main.go` in Task 5). Update `module.modulemap` if the struct name changed from `info` to `gost_info`.

- [ ] **Step 8.2: Update the `start()` function in AppDelegate.swift**

Replace the `start()` method body:

```swift
func start() {
    let yaml = UserDefaults.standard.string(forKey: defaultsArgumentsKey) ?? defaultGostYAML

    var fd = self.logPipe?.fileHandleForWriting.fileDescriptor
    let fdPtr = UnsafeMutablePointer<CLong>.allocate(capacity: 1)
    withUnsafeMutablePointer(to: &fd) { ptr in
        fdPtr.initialize(to: CLong(ptr.pointee!))
    }

    let isFailed = gostRunYaml(
        UnsafeMutablePointer<CChar>(mutating: (yaml as NSString).utf8String),
        UnsafeMutablePointer<CLong>(mutating: fdPtr)
    )

    if isFailed != 0 {
        self.menu?.toOffState()
        stop()
        return
    }

    if let infoPtr = gostInfo() {
        let statusJSON = String(cString: infoPtr.pointee.status_json)
        self.menu?.updateListen(statusJSON)
        infoPtr.deallocate()
    }
    self.menu?.toOnState()
}
```

Also replace the `stop()` call from `gostStop()` — the symbol name is unchanged, verify it still links.

- [ ] **Step 8.3: Build macOS app end-to-end**

```bash
make macos 2>&1 | grep -E "error:|Build succeeded|BUILD FAILED"
```

Expected: `** BUILD SUCCEEDED **`

- [ ] **Step 8.4: Commit**

```bash
git add macos/AppDelegate.swift
git commit -m "feat(macos): call gostRunYaml with YAML config for gost v3"
```

---

## Phase 4 — Android App

### Task 9: Android project skeleton

**Files:** All new files under `android/`

- [ ] **Step 9.1: Create android/.gitignore**

```
.gradle
local.properties
build/
app/build/
*.iml
.idea/
# Generated build artifact — committed to go/, not here
app/libs/gostlib.aar
app/libs/gostlib-sources.jar
```

- [ ] **Step 9.2: Create android/settings.gradle.kts**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "GostX"
include(":app")
```

- [ ] **Step 9.3: Create android/build.gradle.kts**

```kotlin
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
```

- [ ] **Step 9.4: Create android/gradle.properties**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 9.5: Create android/app/build.gradle.kts**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gostx"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gostx"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
}

dependencies {
    // Local .aar from gomobile (built by: cd go && make gostlib.aar)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```

- [ ] **Step 9.6: Create android/app/proguard-rules.pro**

```
# Keep gomobile generated classes
-keep class gostlib.** { *; }
```

- [ ] **Step 9.7: Create android/app/src/main/res/values/strings.xml**

```xml
<resources>
    <string name="app_name">GostX</string>
    <string name="vpn_start">启动 VPN</string>
    <string name="vpn_stop">停止 VPN</string>
    <string name="notification_title">GostX 运行中</string>
    <string name="notification_stop">停止</string>
    <string name="tile_label">GostX VPN</string>
</resources>
```

- [ ] **Step 9.8: Create android/app/src/main/res/values/themes.xml**

```xml
<resources>
    <style name="Theme.GostX" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 9.9: Initialize Gradle wrapper**

```bash
cd android
# If Gradle CLI is available:
gradle wrapper --gradle-version 8.2
# Otherwise copy gradlew + gradle/wrapper/ from any existing Android project.
```

- [ ] **Step 9.10: Create placeholder libs directory**

```bash
mkdir -p android/app/libs
touch android/app/libs/.gitkeep
```

- [ ] **Step 9.11: Commit skeleton**

```bash
git add android/
git commit -m "feat(android): add Android project skeleton"
```

---

### Task 10: Build gostlib.aar and wire it into Android

- [ ] **Step 10.1: Install gomobile (if not already installed)**

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
```

Expected: `gomobile init` exits 0.

- [ ] **Step 10.2: Build the .aar**

```bash
cd go && make gostlib.aar
```

Expected: `go/gostlib.aar` created and copied to `android/app/libs/gostlib.aar`.

- [ ] **Step 10.3: Verify the .aar exposes expected symbols**

```bash
unzip -p android/app/libs/gostlib.aar classes.jar | jar tf /dev/stdin 2>/dev/null | grep -i gostlib || \
  unzip -l android/app/libs/gostlib.aar | grep -i gostlib
```

Expected: entries including `gostlib/Gostlib.class` or similar.

- [ ] **Step 10.4: Create minimal AndroidManifest to test Gradle sync**

```xml
<!-- android/app/src/main/AndroidManifest.xml -->
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.GostX">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.GostVpnService"
            android:exported="false"
            android:foregroundServiceType="specialUse"
            android:permission="android.permission.BIND_VPN_SERVICE">
            <intent-filter>
                <action android:name="android.net.VpnService" />
            </intent-filter>
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="vpn" />
        </service>

        <service
            android:name=".tile.GostTileService"
            android:exported="true"
            android:label="@string/tile_label"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>
        </service>

        <receiver
            android:name=".receiver.BootReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

- [ ] **Step 10.5: Create a stub MainActivity so Gradle compiles**

```kotlin
// android/app/src/main/kotlin/com/gostx/MainActivity.kt
package com.gostx
import androidx.activity.ComponentActivity
class MainActivity : ComponentActivity()
```

- [ ] **Step 10.6: Run Gradle build (stub)**

```bash
cd android && ./gradlew assembleDebug 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 10.7: Commit**

```bash
git add android/app/libs/.gitkeep android/app/src/main/AndroidManifest.xml \
        android/app/src/main/kotlin/
git commit -m "build(android): integrate gostlib.aar from gomobile"
```

---

### Task 11: Data layer — ConfigRepository and VpnStateRepository

**Files:**
- Create: `android/app/src/main/kotlin/com/gostx/data/ConfigRepository.kt`
- Create: `android/app/src/main/kotlin/com/gostx/data/VpnStateRepository.kt`
- Create: `android/app/src/main/kotlin/com/gostx/data/LogRepository.kt`
- Create: `android/app/src/test/kotlin/com/gostx/ConfigRepositoryTest.kt`
- Create: `android/app/src/test/kotlin/com/gostx/VpnStateRepositoryTest.kt`

- [ ] **Step 11.1: Write failing tests**

```kotlin
// android/app/src/test/kotlin/com/gostx/ConfigRepositoryTest.kt
package com.gostx

import android.content.SharedPreferences
import com.gostx.data.ConfigRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class ConfigRepositoryTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var repo: ConfigRepository

    @Before fun setup() {
        editor = mock {
            on { putString(any(), any()) } doReturn mock
            on { remove(any()) } doReturn mock
        }
        prefs = mock {
            on { edit() } doReturn editor
            on { getString(any(), anyOrNull()) } doReturn null
        }
        repo = ConfigRepository(prefs)
    }

    @Test fun `getActiveConfig returns default YAML when nothing saved`() {
        val config = repo.getActiveConfig()
        assertTrue("Default config must contain 'services:'", config.contains("services:"))
    }

    @Test fun `saveConfig calls putString with correct key`() {
        repo.saveConfig("p1", "services:\n  - name: test")
        verify(editor).putString("config_profile_p1", "services:\n  - name: test")
        verify(editor).apply()
    }

    @Test fun `getProfiles returns at least default profile`() {
        assertTrue(repo.getProfiles().isNotEmpty())
    }
}
```

```kotlin
// android/app/src/test/kotlin/com/gostx/VpnStateRepositoryTest.kt
package com.gostx

import com.gostx.data.GlobalVpnState
import com.gostx.data.VpnStateRepository
import com.gostx.data.VpnStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VpnStateRepositoryTest {
    @Test fun `initial state is STOPPED`() = runBlocking {
        val repo = VpnStateRepository()
        assertEquals(VpnStatus.STOPPED, repo.state.first().status)
    }

    @Test fun `setState updates flow`() = runBlocking {
        val repo = VpnStateRepository()
        repo.setState(VpnState(VpnStatus.CONNECTED, "127.0.0.1:10808"))
        val s = repo.state.first()
        assertEquals(VpnStatus.CONNECTED, s.status)
        assertEquals("127.0.0.1:10808", s.listenAddr)
    }

    @Test fun `setError stores message`() = runBlocking {
        val repo = VpnStateRepository()
        repo.setError("connection refused")
        assertEquals("connection refused", repo.state.first().error)
    }
}
```

- [ ] **Step 11.2: Run tests (expect fail)**

```bash
cd android && ./gradlew test 2>&1 | grep -E "FAILED|error: unresolved"
```

Expected: FAIL — classes not yet defined.

- [ ] **Step 11.3: Create VpnStateRepository.kt**

```kotlin
// android/app/src/main/kotlin/com/gostx/data/VpnStateRepository.kt
package com.gostx.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnStatus { STOPPED, CONNECTING, CONNECTED, ERROR }

data class VpnState(
    val status: VpnStatus = VpnStatus.STOPPED,
    val listenAddr: String = "",
    val error: String? = null
)

open class VpnStateRepository {
    private val _state = MutableStateFlow(VpnState())
    val state: StateFlow<VpnState> = _state.asStateFlow()

    fun setState(s: VpnState) { _state.value = s }
    fun setConnecting() = setState(VpnState(VpnStatus.CONNECTING))
    fun setConnected(addr: String) = setState(VpnState(VpnStatus.CONNECTED, addr))
    fun setStopped() = setState(VpnState(VpnStatus.STOPPED))
    fun setError(msg: String) = setState(VpnState(VpnStatus.ERROR, error = msg))
}

// Singleton used by Service, ViewModel, and TileService.
object GlobalVpnState : VpnStateRepository()
```

- [ ] **Step 11.4: Create ConfigRepository.kt**

```kotlin
// android/app/src/main/kotlin/com/gostx/data/ConfigRepository.kt
package com.gostx.data

import android.content.SharedPreferences

private const val KEY_PROFILES    = "config_profile_list"
private const val KEY_ACTIVE      = "config_active_profile"
const val DEFAULT_PROFILE_ID      = "default"

val DEFAULT_YAML = """
services:
  - name: socks5-outbound
    addr: :1080
    handler:
      type: socks5
    listener:
      type: tcp
""".trimIndent()

class ConfigRepository(private val prefs: SharedPreferences) {

    fun getProfiles(): List<String> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return listOf(DEFAULT_PROFILE_ID)
        return raw.split(",").filter { it.isNotBlank() }
    }

    fun getActiveProfileId(): String =
        prefs.getString(KEY_ACTIVE, DEFAULT_PROFILE_ID) ?: DEFAULT_PROFILE_ID

    fun setActiveProfile(id: String) = prefs.edit().putString(KEY_ACTIVE, id).apply()

    fun getConfig(profileId: String): String =
        prefs.getString("config_profile_$profileId", null) ?: DEFAULT_YAML

    fun getActiveConfig(): String = getConfig(getActiveProfileId())

    fun saveConfig(profileId: String, yaml: String) {
        prefs.edit().putString("config_profile_$profileId", yaml).apply()
        val profiles = getProfiles().toMutableList()
        if (!profiles.contains(profileId)) {
            profiles.add(profileId)
            prefs.edit().putString(KEY_PROFILES, profiles.joinToString(",")).apply()
        }
    }

    fun deleteProfile(profileId: String) {
        if (profileId == DEFAULT_PROFILE_ID) return
        val profiles = getProfiles().toMutableList().also { it.remove(profileId) }
        prefs.edit()
            .putString(KEY_PROFILES, profiles.joinToString(","))
            .remove("config_profile_$profileId")
            .apply()
        if (getActiveProfileId() == profileId) setActiveProfile(DEFAULT_PROFILE_ID)
    }
}
```

- [ ] **Step 11.5: Create LogRepository.kt**

```kotlin
// android/app/src/main/kotlin/com/gostx/data/LogRepository.kt
package com.gostx.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object LogRepository {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun append(line: String) {
        val updated = (_logs.value + line).takeLast(1000)
        _logs.value = updated
    }

    fun clear() { _logs.value = emptyList() }
}
```

- [ ] **Step 11.6: Run tests**

```bash
cd android && ./gradlew test 2>&1 | grep -E "BUILD|tests were|FAILED"
```

Expected: `BUILD SUCCESSFUL` — all 6 tests pass.

- [ ] **Step 11.7: Commit**

```bash
git add android/app/src/main/kotlin/com/gostx/data/ \
        android/app/src/test/
git commit -m "feat(android): add ConfigRepository, VpnStateRepository, LogRepository"
```

---

### Task 12: Navigation + MainActivity

**Files:**
- Create: `android/app/src/main/kotlin/com/gostx/ui/Navigation.kt`
- Replace: `android/app/src/main/kotlin/com/gostx/MainActivity.kt`
- Create stubs: `HomeScreen.kt`, `LogScreen.kt`, `ConfigScreen.kt`

- [ ] **Step 12.1: Create Navigation.kt**

```kotlin
// android/app/src/main/kotlin/com/gostx/ui/Navigation.kt
package com.gostx.ui

sealed class Screen(val route: String) {
    object Home   : Screen("home")
    object Logs   : Screen("logs")
    object Config : Screen("config")
}
```

- [ ] **Step 12.2: Create screen stubs (will be replaced in Tasks 13-15)**

```kotlin
// android/app/src/main/kotlin/com/gostx/ui/home/HomeScreen.kt
package com.gostx.ui.home
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
@Composable fun HomeScreen() { Text("Home") }
```

```kotlin
// android/app/src/main/kotlin/com/gostx/ui/log/LogScreen.kt
package com.gostx.ui.log
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
@Composable fun LogScreen(onBack: () -> Unit) { Text("Logs") }
```

```kotlin
// android/app/src/main/kotlin/com/gostx/ui/config/ConfigScreen.kt
package com.gostx.ui.config
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.gostx.data.ConfigRepository
@Composable fun ConfigScreen(repo: ConfigRepository, onBack: () -> Unit) { Text("Config") }
```

- [ ] **Step 12.3: Create full MainActivity.kt**

```kotlin
// android/app/src/main/kotlin/com/gostx/MainActivity.kt
package com.gostx

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gostx.data.ConfigRepository
import com.gostx.ui.Screen
import com.gostx.ui.config.ConfigScreen
import com.gostx.ui.home.HomeScreen
import com.gostx.ui.log.LogScreen

class MainActivity : ComponentActivity() {
    private lateinit var configRepository: ConfigRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE)
        configRepository = ConfigRepository(prefs)
        setContent { GostXApp(configRepository) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GostXApp(configRepository: ConfigRepository) {
    val navController = rememberNavController()
    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("GostX") },
                    actions = {
                        IconButton(onClick = { navController.navigate(Screen.Logs.route) }) {
                            Icon(Icons.Filled.Article, contentDescription = "日志")
                        }
                        IconButton(onClick = { navController.navigate(Screen.Config.route) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "配置")
                        }
                    }
                )
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.padding(padding)
            ) {
                composable(Screen.Home.route) { HomeScreen() }
                composable(Screen.Logs.route) { LogScreen(onBack = { navController.popBackStack() }) }
                composable(Screen.Config.route) {
                    ConfigScreen(repo = configRepository, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
```

- [ ] **Step 12.4: Build with stubs**

```bash
cd android && ./gradlew assembleDebug 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 12.5: Commit**

```bash
git add android/app/src/main/kotlin/com/gostx/
git commit -m "feat(android): add MainActivity with TopAppBar navigation"
```

---

### Task 13: HomeScreen

**Files:**
- Replace: `android/app/src/main/kotlin/com/gostx/ui/home/HomeScreen.kt`
- Create: `android/app/src/main/kotlin/com/gostx/ui/home/HomeViewModel.kt`
- Create: `android/app/src/androidTest/kotlin/com/gostx/HomeScreenTest.kt`

- [ ] **Step 13.1: Create HomeViewModel.kt**

```kotlin
// android/app/src/main/kotlin/com/gostx/ui/home/HomeViewModel.kt
package com.gostx.ui.home

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gostx.data.GlobalVpnState
import com.gostx.data.VpnStatus
import com.gostx.service.GostVpnService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    val vpnState = GlobalVpnState.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, GlobalVpnState.state.value)

    fun toggleVpn() {
        val ctx = getApplication<Application>()
        val action = if (vpnState.value.status == VpnStatus.CONNECTED ||
                         vpnState.value.status == VpnStatus.CONNECTING)
            GostVpnService.ACTION_STOP else GostVpnService.ACTION_START

        val intent = Intent(ctx, GostVpnService::class.java).apply { this.action = action }
        if (action == GostVpnService.ACTION_START) ctx.startForegroundService(intent)
        else ctx.startService(intent)
    }
}
```

- [ ] **Step 13.2: Replace HomeScreen.kt**

```kotlin
// android/app/src/main/kotlin/com/gostx/ui/home/HomeScreen.kt
package com.gostx.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gostx.data.VpnStatus

@Composable
fun HomeScreen(vm: HomeViewModel = viewModel()) {
    val state by vm.vpnState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Status row
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dotColor = when (state.status) {
                VpnStatus.CONNECTED  -> Color(0xFF4CAF50)
                VpnStatus.CONNECTING -> Color(0xFFFFC107)
                VpnStatus.ERROR      -> Color(0xFFF44336)
                VpnStatus.STOPPED    -> Color(0xFF9E9E9E)
            }
            Surface(modifier = Modifier.size(12.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = dotColor) {}
            Spacer(Modifier.width(8.dp))
            Text(
                text = when (state.status) {
                    VpnStatus.CONNECTED  -> "运行中"
                    VpnStatus.CONNECTING -> "连接中..."
                    VpnStatus.ERROR      -> "错误"
                    VpnStatus.STOPPED    -> "已停止"
                },
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (state.listenAddr.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("监听: ${state.listenAddr}",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        state.error?.let { err ->
            Spacer(Modifier.height(8.dp))
            Text(err, color = MaterialTheme.colorScheme.error,
                 style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = { vm.toggleVpn() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = state.status != VpnStatus.CONNECTING
        ) {
            Text(
                text = if (state.status == VpnStatus.CONNECTED ||
                           state.status == VpnStatus.CONNECTING) "停止 VPN" else "启动 VPN",
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (state.status == VpnStatus.STOPPED) {
            Spacer(Modifier.height(12.dp))
            Text("首次启动将请求 VPN 权限",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
```

- [ ] **Step 13.3: Create HomeScreenTest.kt**

```kotlin
// android/app/src/androidTest/kotlin/com/gostx/HomeScreenTest.kt
package com.gostx

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gostx.data.GlobalVpnState
import com.gostx.data.VpnStatus
import com.gostx.ui.home.HomeScreen
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun showsStartButtonWhenStopped() {
        GlobalVpnState.setState(VpnState(VpnStatus.STOPPED))
        rule.setContent { HomeScreen() }
        rule.onNodeWithText("启动 VPN").assertIsDisplayed()
    }

    @Test fun showsStopButtonWhenConnected() {
        GlobalVpnState.setState(VpnState(VpnStatus.CONNECTED, "127.0.0.1:10808"))
        rule.setContent { HomeScreen() }
        rule.onNodeWithText("停止 VPN").assertIsDisplayed()
    }

    @Test fun showsListenAddrWhenConnected() {
        GlobalVpnState.setState(VpnState(VpnStatus.CONNECTED, "127.0.0.1:10808"))
        rule.setContent { HomeScreen() }
        rule.onNodeWithText("监听: 127.0.0.1:10808").assertIsDisplayed()
    }
}
```

- [ ] **Step 13.4: Build**

```bash
cd android && ./gradlew assembleDebug 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 13.5: Commit**

```bash
git add android/app/src/main/kotlin/com/gostx/ui/home/ \
        android/app/src/androidTest/
git commit -m "feat(android): add HomeScreen with VPN status and toggle button"
```

---

### Task 14: ConfigScreen

**Files:**
- Replace: `android/app/src/main/kotlin/com/gostx/ui/config/ConfigScreen.kt`
- Create: `android/app/src/main/kotlin/com/gostx/ui/config/ConfigViewModel.kt`
- Create: `android/app/src/androidTest/kotlin/com/gostx/ConfigScreenTest.kt`

- [ ] **Step 14.1: Create ConfigViewModel.kt**

```kotlin
// android/app/src/main/kotlin/com/gostx/ui/config/ConfigViewModel.kt
package com.gostx.ui.config

import androidx.lifecycle.ViewModel
import com.gostx.data.ConfigRepository
import com.gostx.data.DEFAULT_YAML
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConfigUiState(
    val yaml: String = "",
    val profiles: List<String> = emptyList(),
    val activeProfileId: String = "default",
    val validationError: String? = null,
    val isSaved: Boolean = false
)

class ConfigViewModel(private val repo: ConfigRepository) : ViewModel() {
    private val _ui = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _ui.asStateFlow()

    init { load() }

    private fun load() {
        val id = repo.getActiveProfileId()
        _ui.value = ConfigUiState(
            yaml = repo.getConfig(id),
            profiles = repo.getProfiles(),
            activeProfileId = id
        )
    }

    fun onYamlChange(yaml: String) {
        _ui.value = _ui.value.copy(yaml = yaml, validationError = null, isSaved = false)
    }

    fun save() {
        repo.saveConfig(_ui.value.activeProfileId, _ui.value.yaml)
        _ui.value = _ui.value.copy(isSaved = true)
    }

    fun validate(): Boolean {
        return if (!_ui.value.yaml.contains("services:")) {
            _ui.value = _ui.value.copy(validationError = "配置必须包含 services: 字段")
            false
        } else {
            _ui.value = _ui.value.copy(validationError = null)
            true
        }
    }

    fun switchProfile(profileId: String) {
        repo.setActiveProfile(profileId)
        _ui.value = _ui.value.copy(
            activeProfileId = profileId,
            yaml = repo.getConfig(profileId),
            validationError = null
        )
    }

    fun resetToDefault() {
        _ui.value = _ui.value.copy(yaml = DEFAULT_YAML, validationError = null, isSaved = false)
    }
}
```

- [ ] **Step 14.2: Replace ConfigScreen.kt**

```kotlin
// android/app/src/main/kotlin/com/gostx/ui/config/ConfigScreen.kt
package com.gostx.ui.config

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gostx.data.ConfigRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    repo: ConfigRepository,
    onBack: () -> Unit,
    vm: ConfigViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(c: Class<T>): T = ConfigViewModel(repo) as T
    })
) {
    val state by vm.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = { TextButton(onClick = { vm.save() }) { Text("保存") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Profile chips (only shown when multiple profiles exist)
            if (state.profiles.size > 1) {
                Row {
                    state.profiles.forEach { id ->
                        FilterChip(
                            selected = id == state.activeProfileId,
                            onClick = { vm.switchProfile(id) },
                            label = { Text(id) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // YAML text editor
            OutlinedTextField(
                value = state.yaml,
                onValueChange = { vm.onYamlChange(it) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                isError = state.validationError != null
            )

            state.validationError?.let { err ->
                Spacer(Modifier.height(4.dp))
                Text(err, color = MaterialTheme.colorScheme.error,
                     style = MaterialTheme.typography.bodySmall)
            }

            if (state.isSaved) {
                Spacer(Modifier.height(4.dp))
                Text("已保存", color = MaterialTheme.colorScheme.primary,
                     style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = { vm.validate() }) { Text("验证") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.resetToDefault() }) { Text("默认模板") }
            }
        }
    }
}
```

- [ ] **Step 14.3: Create ConfigScreenTest.kt**

```kotlin
// android/app/src/androidTest/kotlin/com/gostx/ConfigScreenTest.kt
package com.gostx

import android.content.SharedPreferences
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gostx.data.ConfigRepository
import com.gostx.ui.config.ConfigScreen
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.*

class ConfigScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun mockRepo(): ConfigRepository {
        val editor: SharedPreferences.Editor = mock { on { putString(any(), any()) } doReturn mock }
        val prefs: SharedPreferences = mock {
            on { edit() } doReturn editor
            on { getString(any(), anyOrNull()) } doReturn null
        }
        return ConfigRepository(prefs)
    }

    @Test fun showsYamlEditorWithDefaultContent() {
        rule.setContent { ConfigScreen(repo = mockRepo(), onBack = {}) }
        rule.onNodeWithText("services:", substring = true).assertIsDisplayed()
    }

    @Test fun saveButtonIsVisible() {
        rule.setContent { ConfigScreen(repo = mockRepo(), onBack = {}) }
        rule.onNodeWithText("保存").assertIsDisplayed()
    }

    @Test fun validateButtonIsVisible() {
        rule.setContent { ConfigScreen(repo = mockRepo(), onBack = {}) }
        rule.onNodeWithText("验证").assertIsDisplayed()
    }
}
```

- [ ] **Step 14.4: Build**

```bash
cd android && ./gradlew assembleDebug 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 14.5: Commit**

```bash
git add android/app/src/main/kotlin/com/gostx/ui/config/ \
        android/app/src/androidTest/kotlin/com/gostx/ConfigScreenTest.kt
git commit -m "feat(android): add ConfigScreen with YAML editor and profile switcher"
```

---

### Task 15: LogScreen

**Files:**
- Replace: `android/app/src/main/kotlin/com/gostx/ui/log/LogScreen.kt`

- [ ] **Step 15.1: Replace LogScreen.kt**

```kotlin
// android/app/src/main/kotlin/com/gostx/ui/log/LogScreen.kt
package com.gostx.ui.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gostx.data.LogRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val logs by LogRepository.logs.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("gostx_log", logs.joinToString("\n")))
                    }) { Text("复制") }
                    TextButton(onClick = { LogRepository.clear() }) { Text("清空") }
                }
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("暂无日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(logs) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                         modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
                }
            }
        }
    }
}
```

- [ ] **Step 15.2: Build**

```bash
cd android && ./gradlew assembleDebug 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 15.3: Commit**

```bash
git add android/app/src/main/kotlin/com/gostx/ui/log/LogScreen.kt
git commit -m "feat(android): add LogScreen with auto-scroll, copy, and clear"
```

---

### Task 16: GostVpnService

**Files:**
- Create: `android/app/src/main/kotlin/com/gostx/notification/NotificationHelper.kt`
- Create: `android/app/src/main/kotlin/com/gostx/service/GostVpnService.kt`

- [ ] **Step 16.1: Create NotificationHelper.kt**

```kotlin
// android/app/src/main/kotlin/com/gostx/notification/NotificationHelper.kt
package com.gostx.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.gostx.MainActivity
import com.gostx.R
import com.gostx.service.GostVpnService

const val CHANNEL_ID = "gostx_vpn"
const val NOTIFICATION_ID = 1

object NotificationHelper {

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, "GostX VPN", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "GostX VPN service status" }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun buildRunningNotification(context: Context, addr: String): Notification {
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            context, 1,
            Intent(context, GostVpnService::class.java).apply { action = GostVpnService.ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(if (addr.isNotEmpty()) "监听: $addr" else "连接中...")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .addAction(0, context.getString(R.string.notification_stop), stopIntent)
            .setOngoing(true)
            .build()
    }
}
```

- [ ] **Step 16.2: Create GostVpnService.kt**

```kotlin
// android/app/src/main/kotlin/com/gostx/service/GostVpnService.kt
package com.gostx.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.gostx.data.ConfigRepository
import com.gostx.data.LogRepository
import com.gostx.data.GlobalVpnState
import com.gostx.data.VpnStatus
import com.gostx.notification.NOTIFICATION_ID
import com.gostx.notification.NotificationHelper
import gostlib.Gostlib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GostVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.gostx.START_VPN"
        const val ACTION_STOP  = "com.gostx.STOP_VPN"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GostVpnService::class.java).apply {
                action = ACTION_START
            })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, GostVpnService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunFd: ParcelFileDescriptor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private lateinit var configRepo: ConfigRepository

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        configRepo = ConfigRepository(getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> scope.launch { startVpn() }
            ACTION_STOP  -> scope.launch { stopVpn() }
        }
        return START_STICKY
    }

    private fun startVpn() {
        GlobalVpnState.setConnecting()
        startForeground(NOTIFICATION_ID, NotificationHelper.buildRunningNotification(this, ""))

        val yaml = configRepo.getActiveConfig()

        // Start gost v3 with internal SOCKS5 injected at 127.0.0.1:10808
        try {
            Gostlib.startVPNMode(yaml)
        } catch (e: Exception) {
            log("gost start failed: ${e.message}")
            GlobalVpnState.setError("gost 启动失败: ${e.message}")
            stopSelf()
            return
        }

        // Build VPN interface
        val builder = Builder()
            .setMtu(1500)
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)          // full tunnel: all IPv4
            .addDnsServer("8.8.8.8")
            .setSession("GostX")
            .setBlocking(false)

        // protect() the socket gost uses so its traffic bypasses the TUN
        // (gost uses tcp/udp sockets internally; we protect by fd if accessible,
        //  but gost v3 with gomobile may need a workaround — protect all sockets
        //  using a global protect hook if available)

        tunFd = builder.establish() ?: run {
            log("Failed to establish VPN interface")
            GlobalVpnState.setError("VPN 接口建立失败")
            Gostlib.stop()
            stopSelf()
            return
        }

        // Start tun2socks
        try {
            Gostlib.startVPN(tunFd!!.fd, 1500)
        } catch (e: Exception) {
            log("tun2socks start failed: ${e.message}")
            GlobalVpnState.setError("tun2socks 启动失败: ${e.message}")
            closeTun()
            Gostlib.stop()
            stopSelf()
            return
        }

        val status = Gostlib.getStatus()
        val addr = parseFirstAddress(status)
        GlobalVpnState.setConnected(addr)
        startForeground(NOTIFICATION_ID, NotificationHelper.buildRunningNotification(this, addr))
        log("VPN started, gost status: $status")

        registerNetworkCallback()
        saveLastRunState(true)
    }

    private fun stopVpn() {
        unregisterNetworkCallback()
        Gostlib.stopVPN()
        Gostlib.stop()
        closeTun()
        GlobalVpnState.setStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        saveLastRunState(false)
        log("VPN stopped")
    }

    private fun closeTun() {
        tunFd?.close()
        tunFd = null
    }

    private fun log(msg: String) = LogRepository.append(msg)

    private fun parseFirstAddress(statusJson: String): String {
        // statusJson example: {"running":true,"addresses":["127.0.0.1:10808"]}
        val match = Regex(""""addresses":\["([^"]+)"""").find(statusJson)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun saveLastRunState(running: Boolean) {
        getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("last_vpn_running", running).apply()
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Network restored after loss — restart VPN if it was running
                if (GlobalVpnState.state.value.status == VpnStatus.CONNECTED) {
                    scope.launch {
                        stopVpn()
                        startVpn()
                    }
                }
            }
        }
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            cb
        )
        networkCallback = cb
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(it)
        }
        networkCallback = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
```

- [ ] **Step 16.3: Build**

```bash
cd android && ./gradlew assembleDebug 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 16.4: Commit**

```bash
git add android/app/src/main/kotlin/com/gostx/service/ \
        android/app/src/main/kotlin/com/gostx/notification/
git commit -m "feat(android): add GostVpnService with tun2socks and network reconnect"
```

---

### Task 17: Quick Settings Tile

**Files:**
- Create: `android/app/src/main/kotlin/com/gostx/tile/GostTileService.kt`

- [ ] **Step 17.1: Create GostTileService.kt**

```kotlin
// android/app/src/main/kotlin/com/gostx/tile/GostTileService.kt
package com.gostx.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.gostx.data.VpnStatus
import com.gostx.service.GostVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GostTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            GlobalVpnState.state.collect { state ->
                qsTile?.apply {
                    state_ = when (state.status) {
                        VpnStatus.CONNECTED  -> Tile.STATE_ACTIVE
                        VpnStatus.CONNECTING -> Tile.STATE_ACTIVE
                        else                 -> Tile.STATE_INACTIVE
                    }
                    updateTile()
                }
            }
        }
    }

    override fun onClick() {
        super.onClick()
        val status = GlobalVpnState.state.value.status
        if (status == VpnStatus.CONNECTED || status == VpnStatus.CONNECTING) {
            GostVpnService.stop(applicationContext)
        } else {
            GostVpnService.start(applicationContext)
        }
    }

    override fun onStopListening() {
        scope.cancel()
        super.onStopListening()
    }
}
```

- [ ] **Step 17.2: Build**

```bash
cd android && ./gradlew assembleDebug 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 17.3: Commit**

```bash
git add android/app/src/main/kotlin/com/gostx/tile/
git commit -m "feat(android): add Quick Settings tile for VPN toggle"
```

---

### Task 18: BootReceiver (auto-start on boot)

**Files:**
- Create: `android/app/src/main/kotlin/com/gostx/receiver/BootReceiver.kt`

- [ ] **Step 18.1: Create BootReceiver.kt**

```kotlin
// android/app/src/main/kotlin/com/gostx/receiver/BootReceiver.kt
package com.gostx.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gostx.service.GostVpnService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val wasRunning = context
            .getSharedPreferences("gostx_prefs", Context.MODE_PRIVATE)
            .getBoolean("last_vpn_running", false)

        if (wasRunning) {
            GostVpnService.start(context)
        }
    }
}
```

- [ ] **Step 18.2: Build final APK**

```bash
cd android && ./gradlew assembleDebug 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL` — APK at `android/app/build/outputs/apk/debug/app-debug.apk`.

```bash
ls -lh android/app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 18.3: Run all unit tests**

```bash
cd android && ./gradlew test 2>&1 | grep -E "BUILD|tests were|FAILED"
```

Expected: `BUILD SUCCESSFUL` — all unit tests pass.

- [ ] **Step 18.4: Commit**

```bash
git add android/app/src/main/kotlin/com/gostx/receiver/
git commit -m "feat(android): add BootReceiver for auto-start on device reboot"
```

---

## Final Verification

- [ ] **Go library: all tests pass**

```bash
cd go && go test ./gostlib/... -v 2>&1 | grep -E "PASS|FAIL|ok"
```

Expected: All tests PASS.

- [ ] **macOS: app builds successfully**

```bash
make macos 2>&1 | grep -E "error:|BUILD SUCCEEDED|BUILD FAILED"
```

Expected: `** BUILD SUCCEEDED **`

- [ ] **Android: debug APK builds**

```bash
make android 2>&1 | grep -E "BUILD|error:"
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Final commit with summary tag**

```bash
git tag -a v1.0.0-android-beta -m "GostX Android client + gost v3 upgrade"
```
