package libgost

import (
	"context"
	"testing"

	gostchain "github.com/go-gost/core/chain"
	xchain "github.com/go-gost/x/chain"
)

// countingChain implements gostchain.Chainer and records Close calls. It
// stands in for the VPN chain, whose node transports hold tunnel resources
// (e.g. dialer/wg's WireGuard device).
type countingChain struct {
	closed int
}

func (c *countingChain) Route(ctx context.Context, network, address string, opts ...gostchain.RouteOption) gostchain.Route {
	return nil
}

func (c *countingChain) Close() error {
	c.closed++
	return nil
}

// Stopping the VPN must release the tunnel chain: the chain is attached only
// to the extracted tungo service, so nothing else closes it on stop, and the
// WireGuard device would otherwise survive (and keep sending keepalives)
// until the next StartGost.
func TestSingTunHandlerCloseClosesRouter(t *testing.T) {
	ch := &countingChain{}
	h := &singTunHandler{
		router: xchain.NewRouter(gostchain.ChainRouterOption(ch)),
	}

	h.close()

	if ch.closed != 1 {
		t.Fatalf("chain closed %d times, want 1", ch.closed)
	}
}

func TestSingTunHandlerCloseWithoutRouter(t *testing.T) {
	h := &singTunHandler{}
	h.close() // must not panic when no router was installed
}
