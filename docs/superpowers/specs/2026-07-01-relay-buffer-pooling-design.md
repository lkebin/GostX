# Relay Buffer Pooling (TCP/UDP) — Design

## Background

The `battery` branch already implements several power-saving measures modeled
after sing-box's approach (Doze pause/wake, tighter GC via `SetMemoryLimit`,
`START_NOT_STICKY`). An overnight (7h) real-device test showed ~14 minutes of
CPU time — an improvement, but with further headroom.

Comparing `libgost/tun.go` against sing-box's relay implementation
(`route/conn.go`, `transport/simple-obfs/*`) surfaced one clear remaining gap:
**relay buffers are not pooled**, unlike sing-box which routes all its
copy paths through a shared, size-classed `sync.Pool` allocator
(`github.com/sagernet/sing/common/buf`).

## Problem

1. **TCP `relay()`** (tun.go) uses raw `io.Copy(dst, src)`. Go's `io.Copy`
   internally allocates a fresh 32KB `[]byte` per call when neither side
   implements `io.WriterTo`/`io.ReaderFrom` (the common case here, since conns
   are sing-tun/gost-wrapped, not raw `*net.TCPConn`). Each TCP session spawns
   two such goroutines (upload/download), so every connection heap-allocates
   64KB that is discarded on close. Under moderate concurrent connection churn
   (typical of mobile app background traffic), this is a meaningful source of
   GC pressure — and GC cycles cost CPU time, which is the resource this
   effort is trying to minimize.

2. **UDP `relayPacketConn()`** (tun.go) already uses the pooled
   `singbuf.NewSize(65535)` pattern for the TUN→upstream read direction
   (reused via `.Reset()`, released via `.Release()` on exit — correct).
   However the upstream→TUN direction uses a raw
   `data := make([]byte, 65535)`, allocated once per UDP session but **never
   pooled**, and sized far larger (65535B) than real-world UDP payloads
   (typically ≤ ~1500B MTU). This wastes memory per session and increases
   heap growth under our tight `SetMemoryLimit` (50MB), forcing more frequent
   GC.

## Goals

- Reduce heap allocation churn in the TCP and UDP relay hot paths.
- Reduce retained memory size for UDP session buffers to something
  proportional to real traffic instead of the theoretical max (65535B).
- Zero behavior change to relay semantics (framing, EOF/close handling,
  error propagation) — this is a pure internal implementation swap.
- No new dependencies.

## Approach

Reuse `github.com/sagernet/sing/common/buf` (imported already as `singbuf`),
which backs `buf.Get(size)`/`buf.Put(buf)` with a real `sync.Pool`-based,
size-classed allocator (11 buckets, 64B→64KB, see
`sing/common/buf/alloc.go`). This is already a project dependency and already
used correctly elsewhere in `tun.go` (UDP read-side). Reusing it avoids
introducing a second, redundant pooling mechanism.

**Alternatives considered:**
- *Hand-rolled `sync.Pool` of `[]byte`*: rejected — duplicates functionality
  already provided and battle-tested by the existing `singbuf` dependency,
  for no additional benefit.
- *`bufio.Reader`/`bufio.Writer` wrapping*: rejected — adds an unnecessary
  buffering layer; `io.CopyBuffer` with a pooled buffer is sufficient and
  matches what `io.Copy` already does internally, minus the allocation.

### 1. TCP `relay()`

Replace:
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
with a version where each goroutine gets a pooled 32KB buffer via
`singbuf.Get(32*1024)`, passes it to `io.CopyBuffer`, and returns it via
`singbuf.Put(buf)` (deferred, so it's released even on panic/early return).
`io.CopyBuffer` still transparently uses `WriterTo`/`ReaderFrom` zero-copy
paths first if the underlying conn types happen to implement them — supplying
a buffer never disables that fast path, it's only a fallback.

### 2. UDP `relayPacketConn()`

Replace the raw `data := make([]byte, 65535)` (upstream→TUN direction) with a
pooled buffer obtained via `singbuf.Get(65535)` at goroutine start, released
via `singbuf.Put()` on exit (deferred).

**Update after implementation review:** the original plan shrank this buffer
to 2048 bytes to reduce the pooled size class. Code review during
implementation caught that this introduces silent datagram truncation: UDP
`Read` follows POSIX `recvfrom` semantics — if the buffer is smaller than
the datagram, the excess is silently discarded with no error surfaced. Real
UDP traffic routinely exceeds 2048B (e.g. EDNS0 DNS responses ~4096B), so
this would have been a correctness regression, not just a memory
optimization. The buffer size was corrected back to 65535 (matching the
TUN→upstream direction), keeping only the pooling benefit (raw `make()` →
`singbuf.Get`/`Put`) without the truncation risk.

## Testing / Validation

- `cd libgost && go test .` — existing suite including `tun_race_test.go`
  (race detector coverage for the trackable-conn close paths this code
  touches) must pass unchanged.
- No new test infrastructure needed: this is an internal buffer-reuse swap
  with identical external behavior (same framing, same close/error
  semantics). Manual/real-device CPU-time comparison (the original overnight
  test methodology) is the practical way to confirm the power benefit, but is
  outside the scope of what can be verified in this sandbox.

## Out of Scope (deferred, not part of this change)

- Further GOGC/SetMemoryLimit tuning (sing-box uses GOGC=10 + 30MB vs our
  GOGC=20 + 50MB) — flagged as a possible follow-up, but more aggressive GC
  increases collector CPU time even as it reduces memory, so it needs
  independent real-device measurement before adopting.
- A conntrack-style periodic OOM killer safety net (sing-box
  `common/conntrack/killer.go`).
- Pausing outbound dial attempts when the default network interface is lost
  (sing-box `NetworkPause`/`NetworkWake` in `route/network.go`).
- Requesting Android's "ignore battery optimizations" exemption (sing-box's
  Android client does this) — deliberately not adopted, since it would
  reduce Doze-mode throttling of the app rather than lean into it, working
  against the Pause/WakeTun design this branch already relies on.
