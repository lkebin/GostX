package libgost

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/sys/unix"

	singtun "github.com/sagernet/sing-tun"
	singbuf "github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	gostchain "github.com/go-gost/core/chain"
	xchain "github.com/go-gost/x/chain"
	"github.com/go-gost/x/registry"
)

// trackableConn pairs a TUN-side connection with its upstream proxy connection.
// Both are closed together when the device enters doze mode, unblocking
// the relay goroutines and allowing the Go runtime to idle.
type trackableConn struct {
	conn     io.Closer
	upstream io.Closer
	mu       sync.Mutex
	closed   atomic.Bool
}

func (t *trackableConn) Close() error {
	if t.closed.Swap(true) {
		return nil
	}
	t.conn.Close()
	t.mu.Lock()
	u := t.upstream
	t.mu.Unlock()
	if u != nil {
		u.Close()
	}
	return nil
}

// setUpstream stores the upstream connection. If the trackableConn was already
// closed (via PauseTun/WakeTun), u is closed immediately to avoid a leak.
func (t *trackableConn) setUpstream(u io.Closer) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed.Load() {
		u.Close()
		return
	}
	t.upstream = u
}

var (
	tunMu      sync.Mutex
	tunRunning bool

	tunStack   singtun.Stack
	tunDevice  singtun.Tun
	tunCancel  context.CancelFunc
	tunHandler *singTunHandler // set by StartTun, read by PauseTun/WakeTun

	pauseMu    sync.Mutex
	pauseTimer *time.Timer

	// Connection counters; read by GetStatus().
	tcpConns    int64
	udpConns    int64
	failedConns int64
)

// tunVPNPrefix must match the address configured in GostVpnService
// (builder.addAddress("10.0.0.2", 24)). The system stack binds its TCP
// listener to this address, so packets arriving on the TUN interface are
// redirected to the local listener via IP header rewriting — no userspace
// TCP/IP stack required.
const tunVPNPrefix = "10.0.0.2/24"

// StartTun creates a sing-tun system stack on the given TUN file descriptor,
// routing TCP/UDP sessions through the gost chain. StartGost must have been
// called first to parse the config and start service listeners.
//
// The system stack binds TCP listeners to the TUN interface address and
// rewrites IP/TCP headers so the OS kernel handles TCP reassembly — unlike
// the former gVisor approach which ran a full userspace TCP/IP stack.
func StartTun(fd int, mtu int) error {
	tunMu.Lock()
	defer tunMu.Unlock()

	if tunRunning {
		return fmt.Errorf("TUN already running; call StopTun() first")
	}
	if fd < 0 {
		return fmt.Errorf("invalid TUN file descriptor: %d", fd)
	}
	if _, err := unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0); err != nil {
		return fmt.Errorf("invalid TUN file descriptor %d: %w", fd, err)
	}

	mu.Lock()
	chainName := vpnChainName
	dnsServiceAddr := vpnDNSServiceAddr
	mu.Unlock()

	return startVPNSingTun(fd, mtu, chainName, dnsServiceAddr)
}

func startVPNSingTun(fd, mtu int, chainName, dnsServiceAddr string) error {
	// Dup the fd so sing-tun owns an independent copy. Android's
	// ParcelFileDescriptor manages the original fd; closing the dup on StopTun
	// does not affect the original.
	dupFd, err := unix.Dup(fd)
	if err != nil {
		log().Errorf("StartTun: dup TUN fd: %v", err)
		return fmt.Errorf("dup TUN fd: %w", err)
	}

	// Resolve the chain from gost's registry (populated by loader.Load in StartGost).
	var chainer gostchain.Chainer
	if chainName != "" {
		chainer = registry.ChainRegistry().Get(chainName)
		if chainer == nil {
			log().Warnf("chain %q not found in registry – traffic will route directly", chainName)
		} else {
			log().Infof("chain %q found, sing-tun stack starting (fd=%d mtu=%d type=%s)", chainName, fd, mtu, tunStackType)
		}
	} else {
		log().Infof("no chain name – stack will route directly (fd=%d mtu=%d type=%s)", fd, mtu, tunStackType)
	}
	router := xchain.NewRouter(
		gostchain.ChainRouterOption(chainer),
	)

	prefix, err := netip.ParsePrefix(tunVPNPrefix)
	if err != nil {
		unix.Close(dupFd)
		log().Errorf("StartTun: parse TUN prefix %q: %v", tunVPNPrefix, err)
		return fmt.Errorf("parse TUN prefix %q: %w", tunVPNPrefix, err)
	}
	tunOptions := singtun.Options{
		FileDescriptor: dupFd,
		MTU:            uint32(mtu),
		Inet4Address:   []netip.Prefix{prefix},
		AutoRoute:      false, // Android VPN API already routes traffic into the TUN.
	}

	device, err := singtun.New(tunOptions)
	if err != nil {
		unix.Close(dupFd)
		log().Errorf("StartTun: create TUN device: %v", err)
		return fmt.Errorf("create TUN device: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	handler := &singTunHandler{
		router:         router,
		dnsServiceAddr: dnsServiceAddr,
	}
	stack, err := singtun.NewStack(tunStackType, singtun.StackOptions{
		Context:    ctx,
		Tun:        device,
		TunOptions: tunOptions,
		UDPTimeout: 30 * time.Second,
		Handler:    handler,
		Logger:     &singLogAdapter{},
	})
	if err != nil {
		cancel()
		device.Close()
		log().Errorf("StartTun: create sing-tun stack (type=%s): %v", tunStackType, err)
		return fmt.Errorf("create sing-tun stack: %w", err)
	}

	if err := stack.Start(); err != nil {
		cancel()
		device.Close()
		log().Errorf("StartTun: start sing-tun stack (type=%s): %v", tunStackType, err)
		return fmt.Errorf("start sing-tun stack: %w", err)
	}
	ifaceName, _ := device.Name()
	log().Infof("sing-tun stack started (type=%s iface=%s tcp_listener=%s, dns=%s)", tunStackType, ifaceName,
		tunVPNPrefix, dnsServiceAddr)

	tunStack = stack
	tunDevice = device
	tunCancel = cancel
	tunHandler = handler
	tunRunning = true
	return nil
}

// StopTun stops the active sing-tun stack and releases the TUN device.
// Safe to call when not running.
func StopTun() error {
	tunMu.Lock()
	defer tunMu.Unlock()

	if !tunRunning {
		return nil
	}

	// Cancel the context first — this signals the stack's goroutines to stop.
	if tunCancel != nil {
		tunCancel()
		tunCancel = nil
	}
	// Close the stack (stops the packet-processing loop and listeners).
	if tunStack != nil {
		tunStack.Close()
		tunStack = nil
	}
	// Close the TUN device (closes the dup'd fd, unblocking any pending reads).
	if tunDevice != nil {
		tunDevice.Close()
		tunDevice = nil
	}

	// Cancel any pending pause/wake timer.
	pauseMu.Lock()
	if pauseTimer != nil {
		pauseTimer.Stop()
		pauseTimer = nil
	}
	pauseMu.Unlock()

	tunHandler = nil
	resetConnCounters()
	drainStaleLogs()

	tunRunning = false
	return nil
}

// singTunHandler implements singtun.Handler, routing every TCP/UDP session
// through a gost chain Router.
type singTunHandler struct {
	router         *xchain.Router
	dnsServiceAddr string // loopback address of the Gost DNS service, e.g. "127.0.0.1:5353"
	activeConns    atomic.Int64

	trackMu sync.Mutex
	tracked []*trackableConn
}

func (h *singTunHandler) track(tc *trackableConn) {
	h.trackMu.Lock()
	h.tracked = append(h.tracked, tc)
	h.trackMu.Unlock()
}

func (h *singTunHandler) untrack(tc *trackableConn) {
	h.trackMu.Lock()
	defer h.trackMu.Unlock()
	for i, t := range h.tracked {
		if t == tc {
			h.tracked[i] = h.tracked[len(h.tracked)-1]
			h.tracked[len(h.tracked)-1] = nil
			h.tracked = h.tracked[:len(h.tracked)-1]
			return
		}
	}
}

func (h *singTunHandler) closeTracked() {
	h.trackMu.Lock()
	tracked := h.tracked
	h.tracked = nil
	h.trackMu.Unlock()
	for _, tc := range tracked {
		tc.Close()
	}
}

// maxActiveTCPConns is a safety ceiling on concurrent TCP sessions.
// Protects against routing-loop goroutine explosion if the socket protector
// is misconfigured, keeping the VPN service alive for graceful shutdown.
const maxActiveTCPConns = 2000

// PrepareConnection is a pre-flight hook called before each new session.
// Returning nil allows the connection; returning ErrDrop silently drops it.
func (h *singTunHandler) PrepareConnection(network string, source, destination M.Socksaddr) error {
	return nil
}

func (h *singTunHandler) NewConnectionEx(ctx context.Context, conn net.Conn, source, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	tc := &trackableConn{conn: conn}
	h.track(tc)
	go func() {
		defer tc.Close()
		defer h.untrack(tc)
		defer func() {
			if onClose != nil {
				onClose(nil)
			}
		}()
		defer func() {
			if r := recover(); r != nil {
				log().Errorf("[tcp] panic: %v", r)
			}
		}()

		// Guard against routing-loop goroutine explosion. If the socket protector
		// fails, gost's upstream connections loop back through the VPN, spawning
		// a goroutine per iteration. Cap at maxActiveTCPConns to keep the service
		// alive and allow graceful shutdown.
		active := h.activeConns.Add(1)
		defer h.activeConns.Add(-1)
		if active > maxActiveTCPConns {
			log().Warnf("[tcp] connection limit (%d) reached, dropping %v->%v",
				maxActiveTCPConns, source, destination)
			atomic.AddInt64(&failedConns, 1)
			return
		}

		dst := net.JoinHostPort(destination.Addr.String(), strconv.Itoa(int(destination.Port)))
		atomic.AddInt64(&tcpConns, 1)

		var upstream net.Conn
		var err error
		if h.dnsServiceAddr != "" &&
			destination.Addr.String() == vpnDNSVirtualAddr &&
			int(destination.Port) == vpnDNSVirtualPort {
			upstream, err = net.Dial("tcp", h.dnsServiceAddr)
		} else {
			upstream, err = h.router.Dial(ctx, "tcp", dst)
		}
		if err != nil {
			atomic.AddInt64(&failedConns, 1)
			return
		}
		tc.setUpstream(upstream)

		relay(tc, conn, upstream)
	}()
}

func (h *singTunHandler) NewPacketConnectionEx(ctx context.Context, conn N.PacketConn, source, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	tc := &trackableConn{conn: conn}
	h.track(tc)
	go func() {
		defer tc.Close()
		defer h.untrack(tc)
		defer func() {
			if onClose != nil {
				onClose(nil)
			}
		}()
		defer func() {
			if r := recover(); r != nil {
				log().Errorf("[udp] panic: %v", r)
			}
		}()

		dst := net.JoinHostPort(destination.Addr.String(), strconv.Itoa(int(destination.Port)))
		atomic.AddInt64(&udpConns, 1)

		var upstream net.Conn
		var err error
		if h.dnsServiceAddr != "" &&
			destination.Addr.String() == vpnDNSVirtualAddr &&
			int(destination.Port) == vpnDNSVirtualPort {
			upstream, err = net.Dial("udp", h.dnsServiceAddr)
		} else {
			upstream, err = h.router.Dial(ctx, "udp", dst)
		}
		if err != nil {
			atomic.AddInt64(&failedConns, 1)
			return
		}
		tc.setUpstream(upstream)

		relayPacketConn(tc, conn, upstream, destination)
	}()
}

// udpUpstreamReadBufferSize matches the max UDP datagram size (65535B).
// UDP sockets truncate silently if the read buffer is smaller than the
// datagram (POSIX recvfrom semantics, no error surfaced) — e.g. EDNS0 DNS
// responses commonly reach ~4096B, well above a "typical MTU" guess, so
// this must stay full-size even though it's now pooled rather than raw
// make()'d. Matches the TUN→upstream direction's buffer size below.
const udpUpstreamReadBufferSize = 65535

// relayPacketConn pipes data bidirectionally between a sing-tun PacketConn
// (one UDP session from the TUN device) and a plain net.Conn (upstream proxy).
// Each Read/Write on the upstream corresponds to one datagram.
// relayPacketConn pipes data bidirectionally between a sing-tun PacketConn
// (one UDP session from the TUN device) and a plain net.Conn (upstream proxy).
// Each Read/Write on the upstream corresponds to one datagram.
//
// As with relay(), either direction closing calls tc.Close() so the other
// blocked read (e.g. an idle UDP session whose upstream QUIC read never
// returns after sing-tun's 30s UDPTimeout) is unblocked and the relay
// terminates, preventing upstream-connection and goroutine leaks.
func relayPacketConn(tc *trackableConn, src N.PacketConn, dst net.Conn, remoteAddr M.Socksaddr) {
	done := make(chan struct{}, 2)

	// TUN → upstream proxy
	go func() {
		defer func() { done <- struct{}{} }()
		buf := singbuf.NewSize(65535)
		defer buf.Release()
		for {
			buf.Reset()
			if _, err := src.ReadPacket(buf); err != nil {
				break
			}
			if _, err := dst.Write(buf.Bytes()); err != nil {
				break
			}
		}
		closeWrite(dst)
		tc.Close()
	}()

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
		tc.Close()
	}()

	<-done
	<-done
	tc.Close()
}

// tcpRelayBufferSize matches io.Copy's own default buffer size (32 KiB),
// so pooling doesn't change the effective copy chunk size — only where the
// buffer comes from.
const tcpRelayBufferSize = 32 * 1024

// relay pipes data bidirectionally between src and dst. When either direction
// reaches EOF/error it closes the whole tracked connection (via tc) so the
// other, possibly-blocked read is unblocked and relay() always returns.
//
// This is essential: a silently-dead peer (common with UDP/QUIC upstreams such
// as hysteria, which never deliver a FIN/RST) leaves one goroutine blocked on
// a read forever. Without the forced tc.Close() here, relay() would never
// return, defer tc.Close() would never run, and the upstream connection +
// goroutines would leak until the VPN is restarted — the classic "degrades
// over time, restart fixes" symptom.
func relay(tc *trackableConn, src, dst net.Conn) {
	done := make(chan struct{}, 2)
	go func() {
		buf := singbuf.Get(tcpRelayBufferSize)
		defer singbuf.Put(buf)
		io.CopyBuffer(dst, src, buf)
		closeWrite(dst)
		tc.Close()
		done <- struct{}{}
	}()
	go func() {
		buf := singbuf.Get(tcpRelayBufferSize)
		defer singbuf.Put(buf)
		io.CopyBuffer(src, dst, buf)
		closeWrite(src)
		tc.Close()
		done <- struct{}{}
	}()
	<-done
	<-done
	tc.Close()
}

// closeWrite signals write-EOF on c if it supports half-close (e.g. TCP).
func closeWrite(c net.Conn) {
	type halfCloser interface {
		CloseWrite() error
	}
	if hc, ok := c.(halfCloser); ok {
		_ = hc.CloseWrite()
	}
}

// singLogAdapter adapts the shared logger to sing's logger.Logger interface.
// Trace is mapped to Debug so that sing-tun internal messages (e.g.
// "unknown session with port N") are visible in the log output.
type singLogAdapter struct{}

func (l *singLogAdapter) Trace(args ...any) { log().Debug(args...) }
func (l *singLogAdapter) Debug(args ...any) { log().Debug(args...) }
func (l *singLogAdapter) Info(args ...any)  { log().Info(args...) }
func (l *singLogAdapter) Warn(args ...any)  { log().Warn(args...) }
func (l *singLogAdapter) Error(args ...any) { log().Error(args...) }
func (l *singLogAdapter) Fatal(args ...any) { log().Fatal(args...) }

// Panic logs and then panics, matching the previous logrus.Panic behaviour.
func (l *singLogAdapter) Panic(args ...any) {
	log().Error(args...)
	panic(fmt.Sprint(args...))
}

// Ensure singLogAdapter satisfies the logger.Logger interface at compile time.
var _ logger.Logger = (*singLogAdapter)(nil)

// PauseTun is called when the device enters doze (idle) mode.
// After a 3-second delay it closes all tracked connections so that relay
// goroutines exit and the Go runtime can idle, saving power.
func PauseTun() {
	pauseMu.Lock()
	defer pauseMu.Unlock()
	if pauseTimer != nil {
		pauseTimer.Stop()
	}
	pauseTimer = time.AfterFunc(3*time.Second, func() {
		tunMu.Lock()
		h := tunHandler
		tunMu.Unlock()
		if h != nil {
			log().Info("[pause] doze mode: closing all tracked connections")
			h.closeTracked()
		}
	})
}

// WakeTun is called when the device exits doze mode.
// After a 3-minute delay it resets tracked connections so that applications
// reconnect through fresh proxy connections.
func WakeTun() {
	pauseMu.Lock()
	defer pauseMu.Unlock()
	if pauseTimer != nil {
		pauseTimer.Stop()
	}
	pauseTimer = time.AfterFunc(3*time.Minute, func() {
		tunMu.Lock()
		h := tunHandler
		tunMu.Unlock()
		if h != nil {
			log().Info("[wake] doze ended: resetting connections")
			h.closeTracked()
		}
	})
}

// ResetTunConnections immediately closes all tracked connections.
// Exported for manual reset (e.g. network change handling).
func ResetTunConnections() {
	tunMu.Lock()
	h := tunHandler
	tunMu.Unlock()
	if h != nil {
		log().Info("[reset] manually closing all tracked connections")
		h.closeTracked()
	}
}

// resetConnCounters zeros the VPN connection counters for a new session.
func resetConnCounters() {
	atomic.StoreInt64(&tcpConns, 0)
	atomic.StoreInt64(&udpConns, 0)
	atomic.StoreInt64(&failedConns, 0)
}
