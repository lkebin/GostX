package libgost

import (
	"io"
	"net"
	"sync"
	"testing"
)

type fakeConn struct{}

func (f *fakeConn) Close() error                       { return nil }
func (f *fakeConn) Read(b []byte) (n int, err error)   { return 0, io.EOF }
func (f *fakeConn) Write(b []byte) (n int, err error)  { return len(b), nil }
func (f *fakeConn) LocalAddr() net.Addr                { return &net.TCPAddr{} }
func (f *fakeConn) RemoteAddr() net.Addr               { return &net.TCPAddr{} }

func TestTrackableConnCloseRace(t *testing.T) {
	for i := 0; i < 1000; i++ {
		tc := &trackableConn{conn: &fakeConn{}}
		var wg sync.WaitGroup
		wg.Add(2)

		go func() {
			defer wg.Done()
			tc.setUpstream(&fakeConn{})
		}()

		go func() {
			defer wg.Done()
			tc.Close()
		}()

		wg.Wait()
	}
}

func TestTrackableConnCloseBeforeUpstreamSet(t *testing.T) {
	upstreamClosed := false
	up := &closeTrackingConn{fakeConn: fakeConn{}, closed: &upstreamClosed}

	tc := &trackableConn{conn: &fakeConn{}}

	// Simulate PauseTun calling Close() before the goroutine assigns upstream.
	tc.Close()

	// The connection goroutine finishes dialing and calls setUpstream.
	// setUpstream must detect closed==true and close the upstream immediately.
	tc.setUpstream(up)

	if !upstreamClosed {
		t.Error("setUpstream did not close upstream after Close(): resource leak")
	}
}

type closeTrackingConn struct {
	fakeConn
	closed *bool
}

func (c *closeTrackingConn) Close() error {
	*c.closed = true
	return nil
}
