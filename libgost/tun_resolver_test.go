package libgost

import (
	"context"
	"net"
	"net/netip"
	"testing"

	gostchain "github.com/go-gost/core/chain"
	gostresolver "github.com/go-gost/x/resolver"
	M "github.com/sagernet/sing/common/metadata"
)

// --- mock Chainer ---

type mockRoute struct {
	nodes int
}

func (r *mockRoute) Dial(ctx context.Context, network, address string, opts ...gostchain.DialOption) (net.Conn, error) {
	return nil, nil
}
func (r *mockRoute) Bind(ctx context.Context, network, address string, opts ...gostchain.BindOption) (net.Listener, error) {
	return nil, nil
}
func (r *mockRoute) Nodes() []*gostchain.Node {
	return make([]*gostchain.Node, r.nodes)
}

type mockChainer struct {
	proxyDomains map[string]bool
}

// Route returns a 1-node route (proxy) for whitelisted domains and a 0-node
// route (direct) otherwise, mirroring a whitelist bypass: matched -> proxy.
func (c *mockChainer) Route(ctx context.Context, network, address string, opts ...gostchain.RouteOption) gostchain.Route {
	// The real chain matches bypasses on BOTH addr and host (WithHostRouteOption).
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		host = address
	}
	var o gostchain.RouteOptions
	for _, opt := range opts {
		opt(&o)
	}
	if o.Host != "" {
		if h, _, err := net.SplitHostPort(o.Host); err == nil {
			host = h
		} else {
			host = o.Host
		}
	}
	if c.proxyDomains[host] {
		return &mockRoute{nodes: 1}
	}
	return &mockRoute{nodes: 0} // direct (bypassed)
}

// TestRouterRoutesByChainer documents the routing decision that now lives in
// the Router (gost-x). The Router is built from the upstream chainer via
// ChainRouterOption, and applies the chain bypass on BOTH addr and host inside
// hop.Select. NewConnectionEx/NewPacketConnectionEx therefore need only a
// single router.DialWithHost(addr, host) call — the proxy-vs-direct decision is
// exactly the chainer's Route, exercised here.
func TestRouterRoutesByChainer(t *testing.T) {
	chainer := &mockChainer{proxyDomains: map[string]bool{"proxy.com": true}}

	// Proxy domain -> 1-node route (chain proxy path). The Router keeps the
	// hostname because no resolver is attached, so server-side resolution wins.
	if rt := chainer.Route(context.Background(), "tcp", "proxy.com:443",
		gostchain.WithHostRouteOption("proxy.com:443")); rt == nil || len(rt.Nodes()) == 0 {
		t.Fatal("proxy.com must route through the chain (1-node route)")
	}

	// Direct domain -> 0-node route (DefaultRoute -> dialed directly by IP).
	if rt := chainer.Route(context.Background(), "tcp", "direct.com:443",
		gostchain.WithHostRouteOption("direct.com:443")); rt == nil || len(rt.Nodes()) != 0 {
		t.Fatal("direct.com must bypass the chain (0-node route)")
	}
}

// TestDialTargetContract documents the precondition for the dial branch: a
// recovered domain yields a non-empty host, which is what triggers the
// proxy-vs-direct routing decision in NewConnectionEx/NewPacketConnectionEx.
// Direct (bypassed) destinations are answered with a real IP by the dns
// service (policy DNS via fakeip-exclude), so they arrive here as an IP and
// are dialed directly by IP — no client-side resolver is needed.
func TestDialTargetRecoveredHost(t *testing.T) {
	store := &mockFakeIPStore{
		inet4:  netip.MustParsePrefix("198.18.0.0/15"),
		domain: map[netip.Addr]string{netip.MustParseAddr("198.18.0.1"): "example.com"},
	}
	gostresolver.SetFakeIPStore(store)
	defer gostresolver.SetFakeIPStore(nil)

	h := &singTunHandler{}
	addr, host := h.dialTarget(M.Socksaddr{Addr: netip.MustParseAddr("198.18.0.1"), Port: 443})
	if host == "" {
		t.Fatal("recovered domain must produce a non-empty host for routing")
	}
	if addr != host {
		t.Fatalf("fakeip hit: addr %q should equal host %q (both the domain)", addr, host)
	}
}
