package stack

import (
	"context"
	"errors"
	"net/netip"
	"strconv"
	"testing"
	"time"
)

func TestTCPNatLookup(t *testing.T) {
	nat := NewTCPNat(context.Background())
	src := netip.MustParseAddrPort("10.0.0.4:12345")
	dst := netip.MustParseAddrPort("1.2.3.4:443")

	port, err := nat.Lookup(src, dst, nil)
	if err != nil {
		t.Fatalf("Lookup: %v", err)
	}
	if port < 10000 {
		t.Fatalf("port = %d, want >= 10000", port)
	}

	// Same source should return same port
	port2, err := nat.Lookup(src, dst, nil)
	if err != nil {
		t.Fatalf("Lookup #2: %v", err)
	}
	if port2 != port {
		t.Fatalf("same source: got port %d, want %d", port2, port)
	}

	// LookupBack should find the session
	sess := nat.LookupBack(port)
	if sess == nil {
		t.Fatal("LookupBack returned nil")
	}
	if sess.Source != src {
		t.Fatalf("session source = %v, want %v", sess.Source, src)
	}
	if sess.Destination != dst {
		t.Fatalf("session destination = %v, want %v", sess.Destination, dst)
	}
}

func TestTCPNatDifferentSources(t *testing.T) {
	nat := NewTCPNat(context.Background())
	src1 := netip.MustParseAddrPort("10.0.0.4:10000")
	src2 := netip.MustParseAddrPort("10.0.0.4:20000")
	dst := netip.MustParseAddrPort("1.2.3.4:443")

	port1, _ := nat.Lookup(src1, dst, nil)
	port2, _ := nat.Lookup(src2, dst, nil)
	if port1 == port2 {
		t.Fatalf("different sources got same port %d", port1)
	}
}

func TestTCPNatPrepareCalledOnce(t *testing.T) {
	nat := NewTCPNat(context.Background())
	src := netip.MustParseAddrPort("10.0.0.4:12345")
	dst := netip.MustParseAddrPort("1.2.3.4:443")

	callCount := 0
	prepare := func() error {
		callCount++
		return nil
	}

	_, err := nat.Lookup(src, dst, prepare)
	if err != nil {
		t.Fatalf("Lookup: %v", err)
	}
	if callCount != 1 {
		t.Fatalf("prepare called %d times, want 1", callCount)
	}

	// Second lookup with same source should NOT call prepare again
	_, err = nat.Lookup(src, dst, prepare)
	if err != nil {
		t.Fatalf("Lookup #2: %v", err)
	}
	if callCount != 1 {
		t.Fatalf("prepare called %d times on second lookup, want 1", callCount)
	}
}

func TestTCPNatPrepareError(t *testing.T) {
	nat := NewTCPNat(context.Background())
	src := netip.MustParseAddrPort("10.0.0.4:12345")
	dst := netip.MustParseAddrPort("1.2.3.4:443")

	prepare := func() error {
		return errors.New("blocked")
	}

	_, err := nat.Lookup(src, dst, prepare)
	if err == nil {
		t.Fatal("expected error from prepare, got nil")
	}

	// Session should not exist after prepare error
	sess := nat.LookupBack(10000)
	if sess != nil {
		t.Fatal("session exists after prepare error")
	}
}

func TestTCPNatLookupBackUnknown(t *testing.T) {
	nat := NewTCPNat(context.Background())
	sess := nat.LookupBack(65000)
	if sess != nil {
		t.Fatal("LookupBack unknown port should return nil")
	}
}

// TestTCPNatWraparoundNoCollision verifies that when nextPort wraps
// around from 65535, the allocator does not hand out a port that is
// still in use. Without this, a long-lived connection on port 10000
// could have its session overwritten by a new connection after the
// counter wraps.
func TestTCPNatWraparoundNoCollision(t *testing.T) {
	nat := NewTCPNat(context.Background())

	// Pre-populate port 10000 as in-use (simulating a long-lived connection).
	inUseSession := &TCPSession{
		Source:      netip.MustParseAddrPort("10.0.0.50:50000"),
		Destination: netip.MustParseAddrPort("9.9.9.9:443"),
	}
	nat.mu.Lock()
	nat.portMap[10000] = inUseSession
	nat.addrMap[connKey{inUseSession.Source, inUseSession.Destination}] = 10000
	nat.nextPort = 65535 // about to wrap
	nat.mu.Unlock()

	dst := netip.MustParseAddrPort("1.2.3.4:443")

	// First lookup: allocates 65535, nextPort wraps.
	src1 := netip.MustParseAddrPort("10.0.0.4:11111")
	port1, err := nat.Lookup(src1, dst, nil)
	if err != nil {
		t.Fatalf("Lookup #1: %v", err)
	}
	if port1 != 65535 {
		t.Fatalf("port1 = %d, want 65535", port1)
	}

	// Second lookup: current buggy code allocates 10000, colliding with
	// the long-lived session. The fix must skip in-use ports.
	src2 := netip.MustParseAddrPort("10.0.0.4:22222")
	port2, err := nat.Lookup(src2, dst, nil)
	if err != nil {
		t.Fatalf("Lookup #2: %v", err)
	}
	if port2 == 0 {
		t.Fatal("allocated port 0 (invalid for TCP)")
	}
	if port2 == 10000 {
		t.Fatal("allocated port 10000 which is still in use — wraparound collision")
	}

	// The original session on port 10000 must be intact.
	sess := nat.LookupBack(10000)
	if sess != inUseSession {
		t.Fatal("in-use session on port 10000 was overwritten")
	}
}

// TestTCPNatNeverAllocatesPort0 verifies the allocator never hands out
// port 0 across many allocations including wraparound.
func TestTCPNatNeverAllocatesPort0(t *testing.T) {
	nat := NewTCPNat(context.Background())
	nat.mu.Lock()
	nat.nextPort = 65530 // close to wrap
	nat.mu.Unlock()

	dst := netip.MustParseAddrPort("1.2.3.4:443")
	for i := 0; i < 20; i++ {
		src := netip.MustParseAddrPort("10.0.0.4:" + strconv.Itoa(30000+i))
		port, err := nat.Lookup(src, dst, nil)
		if err != nil {
			t.Fatalf("Lookup %d: %v", i, err)
		}
		if port == 0 {
			t.Fatalf("allocation %d returned port 0", i)
		}
	}
}

// TestTCPNatSessionTimeoutIsLongEnough verifies the production timeout
// is long enough to keep idle long-lived connections alive (HTTP
// keepalive, SSH, websockets, etc.). 5 minutes was too short and would
// silently break idle connections.
func TestTCPNatSessionTimeoutIsLongEnough(t *testing.T) {
	if natSessionTimeout < 30*time.Minute {
		t.Fatalf("natSessionTimeout = %v, want >= 30m", natSessionTimeout)
	}
}

// TestTCPNatCleanExpiredRespectsTimeout verifies cleanExpired only
// removes sessions older than the timeout, leaving younger ones intact.
func TestTCPNatCleanExpiredRespectsTimeout(t *testing.T) {
	nat := NewTCPNat(context.Background())

	fresh := &TCPSession{
		Source:      netip.MustParseAddrPort("10.0.0.1:1000"),
		Destination: netip.MustParseAddrPort("1.2.3.4:443"),
		LastActive:  time.Now().Add(-(natSessionTimeout - time.Minute)),
	}
	stale := &TCPSession{
		Source:      netip.MustParseAddrPort("10.0.0.1:2000"),
		Destination: netip.MustParseAddrPort("1.2.3.4:443"),
		LastActive:  time.Now().Add(-(natSessionTimeout + time.Minute)),
	}
	nat.mu.Lock()
	nat.portMap[10000] = fresh
	nat.portMap[10001] = stale
	nat.addrMap[connKey{fresh.Source, fresh.Destination}] = 10000
	nat.addrMap[connKey{stale.Source, stale.Destination}] = 10001
	nat.mu.Unlock()

	nat.cleanExpired(natSessionTimeout)

	if nat.LookupBack(10000) == nil {
		t.Error("session under timeout was removed")
	}
	if nat.LookupBack(10001) != nil {
		t.Error("session over timeout was not removed")
	}
}
