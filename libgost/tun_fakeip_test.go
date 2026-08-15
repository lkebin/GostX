package libgost

import (
	"net/netip"
	"testing"

	gostresolver "github.com/go-gost/x/resolver"
	M "github.com/sagernet/sing/common/metadata"
)

// mockFakeIPStore is a minimal fakeip store used to exercise dialTarget's
// fakeip branch. The real store lives in gost-x's internal package, which the
// libgost module cannot import, so the dependency is injected via the exported
// resolver.FakeIPStore interface.
type mockFakeIPStore struct {
	inet4  netip.Prefix
	domain map[netip.Addr]string
}

func (m *mockFakeIPStore) Contains(addr netip.Addr) bool {
	return m.inet4.Contains(addr)
}

func (m *mockFakeIPStore) Lookup(addr netip.Addr) (string, bool) {
	d, ok := m.domain[addr]
	return d, ok
}

func TestDialTargetFakeIPHit(t *testing.T) {
	store := &mockFakeIPStore{
		inet4:  netip.MustParsePrefix("198.18.0.0/15"),
		domain: map[netip.Addr]string{netip.MustParseAddr("198.18.0.1"): "example.com"},
	}
	gostresolver.SetFakeIPStore(store)
	defer gostresolver.SetFakeIPStore(nil)

	h := &singTunHandler{}
	addr, host := h.dialTarget(M.Socksaddr{Addr: netip.MustParseAddr("198.18.0.1"), Port: 443})
	if addr != "example.com:443" {
		t.Fatalf("addr = %q, want example.com:443 (must dial the domain, not the fake IP)", addr)
	}
	if host != "example.com:443" {
		t.Fatalf("host = %q, want example.com:443", host)
	}
}

func TestDialTargetFakeIPUnknown(t *testing.T) {
	// Address inside the fake range but with no mapping: must fall back to the
	// raw address (and not crash).
	store := &mockFakeIPStore{
		inet4:  netip.MustParsePrefix("198.18.0.0/15"),
		domain: map[netip.Addr]string{},
	}
	gostresolver.SetFakeIPStore(store)
	defer gostresolver.SetFakeIPStore(nil)

	h := &singTunHandler{}
	addr, host := h.dialTarget(M.Socksaddr{Addr: netip.MustParseAddr("198.18.0.99"), Port: 443})
	if addr != "198.18.0.99:443" {
		t.Fatalf("addr = %q, want 198.18.0.99:443", addr)
	}
	if host != "" {
		t.Fatalf("host = %q, want empty", host)
	}
}

func TestDialTargetNormalIPNoStore(t *testing.T) {
	// No fakeip store installed: normal IP dialing, no host. Uses a TEST-NET
	// address so it can never collide with reverse-map entries other tests
	// populate via DNS resolution (e.g. the mock-DNS resolver test).
	gostresolver.SetFakeIPStore(nil)

	h := &singTunHandler{}
	addr, host := h.dialTarget(M.Socksaddr{Addr: netip.MustParseAddr("203.0.113.5"), Port: 443})
	if addr != "203.0.113.5:443" {
		t.Fatalf("addr = %q, want 203.0.113.5:443", addr)
	}
	if host != "" {
		t.Fatalf("host = %q, want empty", host)
	}
}
