# Relay Buffer Pooling (TCP/UDP) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce GC/CPU pressure in the VPN relay hot path by routing TCP and UDP relay buffers through the existing pooled allocator (`github.com/sagernet/sing/common/buf`) instead of raw `io.Copy` / `make([]byte, ...)` allocations.

**Architecture:** `libgost/tun.go`'s `relay()` (TCP) and `relayPacketConn()` (UDP) are modified in place to obtain their scratch buffers via `singbuf.Get(size)` / release via `singbuf.Put(buf)`, matching the pattern already used elsewhere in the same file. No new files, no new dependencies, no public API changes.

**Tech Stack:** Go 1.x, `github.com/sagernet/sing/common/buf` (already vendored via `libgost/go.mod`), standard `testing` package, `net.Pipe` for connection fakes.

**Design doc:** `docs/superpowers/specs/2026-07-01-relay-buffer-pooling-design.md`

**Do not run `git commit`** as part of executing this plan — leave changes staged/unstaged for the user to review and commit themselves.

---

### Task 1: Pool the TCP relay buffers in `relay()`

**Files:**
- Modify: `libgost/tun.go:436-450` (the `relay` function)
- Test: Create `libgost/tun_relay_test.go`

- [ ] **Step 1: Write the failing/characterization test**

Create `libgost/tun_relay_test.go` with a regression test that exercises `relay()` end-to-end over `net.Pipe()` pairs, confirming bidirectional data still flows correctly (this must keep passing after the refactor — it's here to catch any accidental behavior change, e.g. dropped bytes or a hang):

```go
package libgost

import (
	"io"
	"net"
	"testing"
	"time"
)

func TestRelayCopiesDataBothDirections(t *testing.T) {
	srcConn, srcPeer := net.Pipe()
	dstConn, dstPeer := net.Pipe()
	defer srcPeer.Close()
	defer dstPeer.Close()

	relayDone := make(chan struct{})
	go func() {
		relay(srcConn, dstConn)
		close(relayDone)
	}()

	// upload: srcPeer -> srcConn -> relay -> dstConn -> dstPeer
	go func() {
		_, _ = srcPeer.Write([]byte("upload"))
	}()
	uploadBuf := make([]byte, len("upload"))
	if _, err := io.ReadFull(dstPeer, uploadBuf); err != nil {
		t.Fatalf("upload read failed: %v", err)
	}
	if string(uploadBuf) != "upload" {
		t.Fatalf("upload mismatch: got %q", uploadBuf)
	}

	// download: dstPeer -> dstConn -> relay -> srcConn -> srcPeer
	go func() {
		_, _ = dstPeer.Write([]byte("download"))
	}()
	downloadBuf := make([]byte, len("download"))
	if _, err := io.ReadFull(srcPeer, downloadBuf); err != nil {
		t.Fatalf("download read failed: %v", err)
	}
	if string(downloadBuf) != "download" {
		t.Fatalf("download mismatch: got %q", downloadBuf)
	}

	// Closing both peers should unblock both io.Copy calls inside relay()
	// (EOF on read), letting relay() return.
	srcPeer.Close()
	dstPeer.Close()

	select {
	case <-relayDone:
	case <-time.After(2 * time.Second):
		t.Fatal("relay() did not return after both peers closed")
	}
}
```

- [ ] **Step 2: Run the test to verify it currently passes**

Run: `cd libgost && go test ./... -run TestRelayCopiesDataBothDirections -v`
Expected: `PASS` (this confirms the test itself is correct against the *current*, pre-refactor `relay()` implementation, since we are not changing external behavior — only the internal buffer source).

- [ ] **Step 3: Refactor `relay()` to use pooled buffers**

In `libgost/tun.go`, replace:

```go
func relay(src, dst net.Conn) {
	done := make(chan struct{}, 2)
	go func() {
		io.Copy(dst, src)
		closeWrite(dst)
		done <- struct{}{}
	}()
	go func() {
		io.Copy(src, dst)
		closeWrite(src)
		done <- struct{}{}
	}()
	<-done
	<-done
}
```

with:

```go
// tcpRelayBufferSize matches io.Copy's own default buffer size (32 KiB),
// so pooling doesn't change the effective copy chunk size — only where the
// buffer comes from.
const tcpRelayBufferSize = 32 * 1024

func relay(src, dst net.Conn) {
	done := make(chan struct{}, 2)
	go func() {
		buf := singbuf.Get(tcpRelayBufferSize)
		io.CopyBuffer(dst, src, buf)
		singbuf.Put(buf)
		closeWrite(dst)
		done <- struct{}{}
	}()
	go func() {
		buf := singbuf.Get(tcpRelayBufferSize)
		io.CopyBuffer(src, dst, buf)
		singbuf.Put(buf)
		closeWrite(src)
		done <- struct{}{}
	}()
	<-done
	<-done
}
```

`singbuf` is already imported in `tun.go` (`singbuf "github.com/sagernet/sing/common/buf"`), so no import changes are needed. `io.CopyBuffer` still transparently uses `WriterTo`/`ReaderFrom` zero-copy paths first if the underlying conn types implement them — supplying a buffer only affects the fallback path, so this cannot regress any existing zero-copy behavior.

- [ ] **Step 4: Run the test to verify it still passes**

Run: `cd libgost && go test ./... -run TestRelayCopiesDataBothDirections -v`
Expected: `PASS`

---

### Task 2: Pool and right-size the UDP relay buffer in `relayPacketConn()`

**Files:**
- Modify: `libgost/tun.go:388-431` (the `relayPacketConn` function)
- Test: Modify `libgost/tun_relay_test.go` (created in Task 1)

- [ ] **Step 1: Write the failing/characterization test**

First, update the `import` block at the top of `libgost/tun_relay_test.go` (created in Task 1) to add the two new packages needed by the UDP fake. Replace the existing import block:

```go
import (
	"io"
	"net"
	"testing"
	"time"
)
```

with:

```go
import (
	"io"
	"net"
	"testing"
	"time"

	singbuf "github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
)
```

Then append a fake `N.PacketConn` and a regression test for `relayPacketConn()` to the end of the file:

```go
// fakePacketConn is a minimal N.PacketConn test double. Packets "sent from
// the TUN device" are pushed into fromTun; packets the code under test
// writes back to the TUN device land in toTun.
type fakePacketConn struct {
	fromTun chan []byte
	toTun   chan []byte
	closed  chan struct{}
}

func newFakePacketConn() *fakePacketConn {
	return &fakePacketConn{
		fromTun: make(chan []byte, 4),
		toTun:   make(chan []byte, 4),
		closed:  make(chan struct{}),
	}
}

func (f *fakePacketConn) ReadPacket(buffer *singbuf.Buffer) (M.Socksaddr, error) {
	select {
	case data := <-f.fromTun:
		_, _ = buffer.Write(data)
		return M.Socksaddr{}, nil
	case <-f.closed:
		return M.Socksaddr{}, io.EOF
	}
}

func (f *fakePacketConn) WritePacket(buffer *singbuf.Buffer, destination M.Socksaddr) error {
	data := append([]byte(nil), buffer.Bytes()...)
	buffer.Release()
	select {
	case f.toTun <- data:
		return nil
	case <-f.closed:
		return io.ErrClosedPipe
	}
}

func (f *fakePacketConn) Close() error {
	select {
	case <-f.closed:
	default:
		close(f.closed)
	}
	return nil
}

func (f *fakePacketConn) LocalAddr() net.Addr               { return &net.UDPAddr{} }
func (f *fakePacketConn) SetDeadline(t time.Time) error      { return nil }
func (f *fakePacketConn) SetReadDeadline(t time.Time) error  { return nil }
func (f *fakePacketConn) SetWriteDeadline(t time.Time) error { return nil }

func TestRelayPacketConnCopiesDataBothDirections(t *testing.T) {
	src := newFakePacketConn()
	dstConn, dstPeer := net.Pipe()
	defer dstPeer.Close()

	done := make(chan struct{})
	go func() {
		relayPacketConn(src, dstConn, M.Socksaddr{})
		close(done)
	}()

	// TUN -> upstream: push a "packet" into src.fromTun, expect it on dstPeer.
	src.fromTun <- []byte("ping")
	readBuf := make([]byte, len("ping"))
	if _, err := io.ReadFull(dstPeer, readBuf); err != nil {
		t.Fatalf("tun->upstream read failed: %v", err)
	}
	if string(readBuf) != "ping" {
		t.Fatalf("tun->upstream mismatch: got %q", readBuf)
	}

	// upstream -> TUN: write from dstPeer, expect a packet on src.toTun.
	go func() {
		_, _ = dstPeer.Write([]byte("pong"))
	}()
	select {
	case data := <-src.toTun:
		if string(data) != "pong" {
			t.Fatalf("upstream->tun mismatch: got %q", data)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("upstream->tun packet not received")
	}

	src.Close()
	dstPeer.Close()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("relayPacketConn did not return after both sides closed")
	}
}
```

- [ ] **Step 2: Run the test to verify it currently passes**

Run: `cd libgost && go test ./... -run TestRelayPacketConnCopiesDataBothDirections -v`
Expected: `PASS` (confirms the fake and test are correct against the current implementation before refactor).

- [ ] **Step 3: Refactor `relayPacketConn()`'s upstream-read buffer**

In `libgost/tun.go`, replace the upstream→TUN goroutine body inside `relayPacketConn`:

```go
	// upstream proxy → TUN
	go func() {
		defer func() { done <- struct{}{} }()
		data := make([]byte, 65535)
		for {
			n, err := dst.Read(data)
			if n > 0 {
				pkt := singbuf.NewSize(n)
				pkt.Write(data[:n])
				// WritePacket takes ownership of pkt; do not Release on success.
				if werr := src.WritePacket(pkt, remoteAddr); werr != nil {
					pkt.Release()
					break
				}
			}
			if err != nil {
				break
			}
		}
	}()
```

with:

```go
	// upstream proxy → TUN
	go func() {
		defer func() { done <- struct{}{} }()
		data := singbuf.Get(udpUpstreamReadBufferSize)
		defer singbuf.Put(data)
		for {
			n, err := dst.Read(data)
			if n > 0 {
				pkt := singbuf.NewSize(n)
				pkt.Write(data[:n])
				// WritePacket takes ownership of pkt; do not Release on success.
				if werr := src.WritePacket(pkt, remoteAddr); werr != nil {
					pkt.Release()
					break
				}
			}
			if err != nil {
				break
			}
		}
	}()
```

Also add this package-scope constant directly above the `relayPacketConn` function declaration (same placement style as the existing `maxActiveTCPConns` constant above `PrepareConnection`):

```go
// udpUpstreamReadBufferSize is sized for typical UDP MTUs (~1500B), well
// above common payload sizes but far smaller than the theoretical 65535B
// max — this keeps the pooled buffer in a much smaller size class, reducing
// retained memory under the app's tight SetMemoryLimit.
const udpUpstreamReadBufferSize = 2048
```

- [ ] **Step 4: Run the test to verify it still passes**

Run: `cd libgost && go test ./... -run TestRelayPacketConnCopiesDataBothDirections -v`
Expected: `PASS`

---

### Task 3: Full regression pass

**Files:** None (verification only)

- [ ] **Step 1: Run the full libgost test suite**

Run: `cd libgost && go test . -v`
Expected: All tests pass, including the pre-existing `TestTrackableConnCloseRace`, `TestTrackableConnCloseBeforeUpstreamSet` (in `tun_race_test.go`), `TestStopTunWhenNotStarted`, `TestStartTunInvalidFd` (in `tun_test.go`), the full `libgost_test.go` suite, and the two new tests from Tasks 1–2.

- [ ] **Step 2: Run with the race detector**

Run: `cd libgost && go test . -race`
Expected: `PASS`, no data race reports. This matters because `relay()`/`relayPacketConn()` now share pooled buffer slices with `singbuf`'s internal `sync.Pool`, and the race detector is the right tool to confirm no buffer is read/written concurrently after being returned to the pool.

- [ ] **Step 3: Build the package to confirm no compile errors**

Run: `cd libgost && go build ./...`
Expected: exits 0, no output.

---

## Out of Scope (per design doc)

Do not implement in this plan — these were explicitly deferred during brainstorming:
- GOGC/SetMemoryLimit re-tuning (needs real-device measurement first).
- A conntrack-style periodic OOM killer.
- Pausing outbound dials when the default network interface is lost.
- Requesting Android's "ignore battery optimizations" exemption.
