package libgost

import (
	"io"
	"net"
	"testing"
	"time"

	singbuf "github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
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
