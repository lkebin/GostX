package libgost

import (
	"io"
	"net"
	"sync"
	"testing"
	"time"
)

// hangConn is a net.Conn whose Read blocks until Close() is called. It models
// a real-world peer that has died silently (e.g. a UDP/QUIC upstream whose
// remote end vanished without sending a FIN/RST): the local socket keeps its
// read blocked, never returning an error or EOF.
type hangConn struct {
	mu      sync.Mutex
	closed  bool
	closeCh chan struct{}
}

func newHangConn() *hangConn { return &hangConn{closeCh: make(chan struct{})} }

func (c *hangConn) Read(b []byte) (int, error) {
	<-c.closeCh
	return 0, io.EOF
}

func (c *hangConn) Write(b []byte) (int, error) {
	select {
	case <-c.closeCh:
		return 0, io.ErrClosedPipe
	default:
	}
	return len(b), nil
}

func (c *hangConn) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.closed {
		c.closed = true
		close(c.closeCh)
	}
	return nil
}

func (c *hangConn) LocalAddr() net.Addr                { return &net.TCPAddr{} }
func (c *hangConn) RemoteAddr() net.Addr               { return &net.TCPAddr{} }
func (c *hangConn) SetDeadline(t time.Time) error      { return nil }
func (c *hangConn) SetReadDeadline(t time.Time) error  { return nil }
func (c *hangConn) SetWriteDeadline(t time.Time) error { return nil }

// TestRelayLeaksWhenUpstreamReadHangs reproduces the production failure mode
// for UDP/QUIC (hysteria) upstreams: the app-side connection closes (EOF), so
// the upload direction finishes, but the upstream read blocks forever because
// the peer died silently. relay() waits for BOTH directions to finish before
// returning, so it never returns, defer tc.Close() never runs, and the
// upstream connection + goroutines leak. Over time these accumulate and the
// whole VPN slows down / drops — exactly the "degrades over time, restart
// fixes" symptom. TCP peers usually deliver a RST/EOF so the read unblocks;
// UDP/QUIC peers do not, which is why hysteria is hit hardest.
func TestRelayLeaksWhenUpstreamReadHangs(t *testing.T) {
	// app-side connection: closed immediately so the upload direction sees EOF.
	appConn, appPeer := net.Pipe()
	defer appConn.Close()
	defer appPeer.Close()
	appPeer.Close() // EOF on appConn.Read

	// upstream: blocks on Read until Close — models a silently-dead peer.
	upstream := newHangConn()

	tc := &trackableConn{conn: appConn}
	tc.setUpstream(upstream)

	relayDone := make(chan struct{})
	go func() {
		relay(tc, appConn, upstream)
		close(relayDone)
	}()

	// Give relay() a chance to finish. On the FIXED code, the upload direction
	// finishing must close the upstream so the blocked read unblocks and relay()
	// returns. On the CURRENT code, relay() hangs forever waiting on the blocked
	// upstream read, so tc.Close() (which would close the upstream) never runs.
	select {
	case <-relayDone:
		// relay() returned — good (only possible after the fix).
	case <-time.After(1 * time.Second):
		// relay() is still blocked; the upstream must not be leaked.
	}

	if !upstream.closed {
		t.Fatalf("DEFECT: upstream connection leaked (never closed) when its read blocked on a silently-dead peer; relay() never terminated so tc.Close() never ran")
	}
}
