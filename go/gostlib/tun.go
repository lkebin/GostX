package gostlib

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

	"libgost/gostlib/stack"

	gostchain "github.com/go-gost/core/chain"
	xchain "github.com/go-gost/x/chain"
	"github.com/go-gost/x/registry"
	"github.com/sirupsen/logrus"
)

var (
	tunMu      sync.Mutex
	tunRunning bool

	tunStack  *stack.Stack
	tunDevice *stack.TunDevice
	tunCancel context.CancelFunc

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

// StartTun creates a system stack on the given TUN file descriptor,
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

	return startVPNStack(fd, mtu, chainName, dnsServiceAddr)
}

func startVPNStack(fd, mtu int, chainName, dnsServiceAddr string) error {
	dupFd, err := unix.Dup(fd)
	if err != nil {
		return fmt.Errorf("dup TUN fd: %w", err)
	}

	var chainer gostchain.Chainer
	if chainName != "" {
		chainer = registry.ChainRegistry().Get(chainName)
		if chainer == nil {
			logrus.Warnf("chain %q not found in registry – traffic will route directly", chainName)
		} else {
			logrus.Infof("chain %q found, system stack starting (fd=%d mtu=%d)", chainName, fd, mtu)
		}
	} else {
		logrus.Infof("no chain name – system stack will route directly (fd=%d mtu=%d)", fd, mtu)
	}
	router := xchain.NewRouter(
		gostchain.ChainRouterOption(chainer),
	)

	prefix, err := netip.ParsePrefix(tunVPNPrefix)
	if err != nil {
		unix.Close(dupFd)
		return fmt.Errorf("parse TUN prefix %q: %w", tunVPNPrefix, err)
	}

	device, err := stack.NewTunDevice(dupFd, mtu, prefix)
	if err != nil {
		unix.Close(dupFd)
		return fmt.Errorf("create TUN device: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	s, err := stack.NewStack(ctx, device, &vpnHandler{
		router:         router,
		dnsServiceAddr: dnsServiceAddr,
	}, 30*time.Second)
	if err != nil {
		cancel()
		device.Close()
		return fmt.Errorf("create stack: %w", err)
	}

	if err := s.Start(); err != nil {
		cancel()
		device.Close()
		return fmt.Errorf("start stack: %w", err)
	}
	logrus.Infof("system stack started (tcp_listener=%s/24, dns=%s)",
		tunVPNPrefix, dnsServiceAddr)

	tunStack = s
	tunDevice = device
	tunCancel = cancel
	tunRunning = true
	return nil
}

// StopTun stops the active system stack and releases the TUN device.
// Safe to call when not running.
func StopTun() error {
	tunMu.Lock()
	defer tunMu.Unlock()

	if !tunRunning {
		return nil
	}

	if tunCancel != nil {
		tunCancel()
		tunCancel = nil
	}
	if tunStack != nil {
		tunStack.Close()
		tunStack = nil
	}
	if tunDevice != nil {
		tunDevice.Close()
		tunDevice = nil
	}

	resetConnCounters()
	drainStaleLogs()

	tunRunning = false
	return nil
}

// vpnHandler implements stack.Handler, routing every TCP/UDP session
// through a gost chain Router.
type vpnHandler struct {
	router         *xchain.Router
	dnsServiceAddr string
	activeConns    atomic.Int64
}

const maxActiveTCPConns = 2000

func (h *vpnHandler) PrepareConnection(network string, source, destination netip.AddrPort) error {
	return nil
}

func (h *vpnHandler) NewConnection(ctx context.Context, conn stack.TCPConn, source, destination netip.AddrPort) {
	go func() {
		defer conn.Close()
		defer func() {
			if r := recover(); r != nil {
				logrus.Errorf("[tcp] panic: %v", r)
			}
		}()

		active := h.activeConns.Add(1)
		defer h.activeConns.Add(-1)
		if active > maxActiveTCPConns {
			logrus.Warnf("[tcp] connection limit (%d) reached, dropping %v->%v",
				maxActiveTCPConns, source, destination)
			atomic.AddInt64(&failedConns, 1)
			return
		}

		dst := net.JoinHostPort(destination.Addr().String(), strconv.Itoa(int(destination.Port())))
		n := atomic.AddInt64(&tcpConns, 1)
		logrus.Debugf("[tcp#%d] dial %s", n, dst)

		var upstream net.Conn
		var err error
		if h.dnsServiceAddr != "" &&
			destination.Addr().String() == vpnDNSVirtualAddr &&
			int(destination.Port()) == vpnDNSVirtualPort {
			upstream, err = net.Dial("tcp", h.dnsServiceAddr)
		} else {
			upstream, err = h.router.Dial(ctx, "tcp", dst)
		}
		if err != nil {
			atomic.AddInt64(&failedConns, 1)
			logrus.Errorf("[tcp#%d] dial %s failed: %v", n, dst, err)
			return
		}
		defer upstream.Close()
		logrus.Infof("[tcp#%d] connected %s, relaying", n, dst)

		var up, down int64
		relayDone := make(chan struct{})
		go func() {
			t := time.NewTimer(3 * time.Second)
			defer t.Stop()
			select {
			case <-t.C:
				logrus.Infof("[tcp#%d] heartbeat %s up=%dB down=%dB (still relaying)",
					n, dst, atomic.LoadInt64(&up), atomic.LoadInt64(&down))
			case <-relayDone:
			}
		}()
		relay(conn, upstream, &up, &down)
		close(relayDone)
		logrus.Infof("[tcp#%d] done %s up=%dB down=%dB", n, dst, up, down)
	}()
}

func (h *vpnHandler) NewPacketConnection(ctx context.Context, conn stack.PacketConn, source, destination netip.AddrPort) {
	go func() {
		defer conn.Close()
		defer func() {
			if r := recover(); r != nil {
				logrus.Errorf("[udp] panic: %v", r)
			}
		}()

		dst := net.JoinHostPort(destination.Addr().String(), strconv.Itoa(int(destination.Port())))
		n := atomic.AddInt64(&udpConns, 1)
		logrus.Debugf("[udp#%d] dial %s", n, dst)

		var upstream net.Conn
		var err error
		if h.dnsServiceAddr != "" &&
			destination.Addr().String() == vpnDNSVirtualAddr &&
			int(destination.Port()) == vpnDNSVirtualPort {
			upstream, err = net.Dial("udp", h.dnsServiceAddr)
		} else {
			upstream, err = h.router.Dial(ctx, "udp", dst)
		}
		if err != nil {
			atomic.AddInt64(&failedConns, 1)
			logrus.Errorf("[udp#%d] dial %s failed: %v", n, dst, err)
			return
		}
		defer upstream.Close()

		relayPacketConn(conn, upstream)
	}()
}

// relayPacketConn pipes data bidirectionally between a stack PacketConn
// (one UDP session from the TUN device) and a plain net.Conn (upstream proxy).
// Each Read/Write on the upstream corresponds to one datagram.
func relayPacketConn(src stack.PacketConn, dst net.Conn) {
	done := make(chan struct{}, 2)

	// TUN → upstream proxy
	go func() {
		defer func() { done <- struct{}{} }()
		for {
			data, _, err := src.ReadPacket()
			if err != nil {
				break
			}
			if _, err := dst.Write(data); err != nil {
				break
			}
		}
	}()

	// upstream proxy → TUN
	go func() {
		defer func() { done <- struct{}{} }()
		data := make([]byte, 65535)
		for {
			n, err := dst.Read(data)
			if n > 0 {
				if werr := src.WritePacket(data[:n]); werr != nil {
					break
				}
			}
			if err != nil {
				break
			}
		}
	}()

	<-done
	<-done
}

// relay pipes data bidirectionally between src and dst. upBytes counts data
// flowing from src (app) to dst (upstream); downBytes counts the reverse.
func relay(src, dst io.ReadWriter, upBytes, downBytes *int64) {
	done := make(chan struct{}, 2)
	go func() {
		n, _ := io.Copy(dst, src)
		atomic.StoreInt64(upBytes, n)
		closeWrite(dst)
		done <- struct{}{}
	}()
	go func() {
		n, _ := io.Copy(src, dst)
		atomic.StoreInt64(downBytes, n)
		closeWrite(src)
		done <- struct{}{}
	}()
	<-done
	<-done
}

func closeWrite(c any) {
	type halfCloser interface {
		CloseWrite() error
	}
	if hc, ok := c.(halfCloser); ok {
		_ = hc.CloseWrite()
	}
}

func resetConnCounters() {
	atomic.StoreInt64(&tcpConns, 0)
	atomic.StoreInt64(&udpConns, 0)
	atomic.StoreInt64(&failedConns, 0)
}
