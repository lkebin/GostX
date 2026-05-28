package gostlib

import (
	"context"
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"sync/atomic"

	"golang.org/x/sys/unix"

	// gVisor link endpoint (used by the tungo path)
	gvfdbased "gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/stack"

	// gost chain router (used by the tungo path)
	gostchain "github.com/go-gost/core/chain"
	xchain "github.com/go-gost/x/chain"
	"github.com/go-gost/x/registry"

	// gVisor userspace TCP/UDP stack (shared by both paths)
	"github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/adapter"

	// tun2socks engine (legacy path only – backward compat with old configs)
	"github.com/xjasonlyu/tun2socks/v2/engine"
)

var (
	tunMu      sync.Mutex
	tunRunning bool

	// tungo (gVisor) path state
	tunStack *stack.Stack
	tunDupFd int // dup'd fd owned by the gVisor endpoint; closed on StopVPN

	// legacy tun2socks engine path
	tunUseLegacy bool
)

// StartVPN starts VPN packet routing.
//
// If StartVPNMode detected a tungo service in the config, the gVisor userspace
// stack is used: TCP/UDP sessions are dispatched directly through the gost
// chain router – no extra proxy port is required.
//
// Otherwise the legacy tun2socks engine is used (routes through the injected
// SOCKS5 listener at 127.0.0.1:10808) for backward compatibility with configs
// that pre-date the tungo service type.
//
// Call StartVPNMode() successfully before calling StartVPN().
func StartVPN(fd int, mtu int) error {
	tunMu.Lock()
	defer tunMu.Unlock()

	if tunRunning {
		return fmt.Errorf("VPN already running; call StopVPN() first")
	}
	if fd < 0 {
		return fmt.Errorf("invalid TUN file descriptor: %d", fd)
	}
	if _, err := unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0); err != nil {
		return fmt.Errorf("invalid TUN file descriptor %d: %w", fd, err)
	}

	mu.Lock()
	chainName := vpnChainName
	mu.Unlock()

	if chainName != "" {
		return startVPNGVisor(fd, mtu, chainName)
	}
	return startVPNLegacy(fd, mtu)
}

// startVPNGVisor creates a gVisor userspace network stack directly on the
// Android VPN fd. Each TCP/UDP session is routed through the named gost chain
// via a gostTransportHandler – no SOCKS5 port is opened.
func startVPNGVisor(fd, mtu int, chainName string) error {
	// Dup the fd so the gVisor endpoint owns an independent copy. Android's
	// ParcelFileDescriptor manages the original fd; closing the dup on StopVPN
	// does not affect the original.
	dupFd, err := unix.Dup(fd)
	if err != nil {
		return fmt.Errorf("dup TUN fd: %w", err)
	}

	// Resolve the chain from gost's registry (populated by loader.Load in StartVPNMode).
	var chainer gostchain.Chainer
	if chainName != "" {
		chainer = registry.ChainRegistry().Get(chainName)
		if chainer == nil {
			logVPN("[warn] chain %q not found in registry – traffic will route directly", chainName)
		} else {
			logVPN("[info] chain %q found, gVisor stack starting (fd=%d mtu=%d)", chainName, fd, mtu)
		}
	} else {
		logVPN("[info] no chain name – gVisor stack will route directly (fd=%d mtu=%d)", fd, mtu)
	}
	router := xchain.NewRouter(gostchain.ChainRouterOption(chainer))

	// Build the gVisor link endpoint directly on the raw fd (TUN, no Ethernet header).
	ep, err := gvfdbased.New(&gvfdbased.Options{
		FDs:            []int{dupFd},
		MTU:            uint32(mtu),
		EthernetHeader: false,
	})
	if err != nil {
		unix.Close(dupFd)
		return fmt.Errorf("create gVisor link endpoint: %w", err)
	}

	gvStack, err := core.CreateStack(&core.Config{
		LinkEndpoint:     ep,
		TransportHandler: &gostTransportHandler{router: router},
	})
	if err != nil {
		unix.Close(dupFd)
		return fmt.Errorf("create gVisor stack: %w", err)
	}

	tunStack = gvStack
	tunDupFd = dupFd
	tunUseLegacy = false
	tunRunning = true
	return nil
}

// startVPNLegacy uses the tun2socks engine for configs that do not contain a
// tungo service (backward compatibility).
func startVPNLegacy(fd, mtu int) error {
	// Dup so tun2socks FD.Close() only closes its own copy.
	tunFd := fd
	if dup, err := unix.Dup(fd); err == nil {
		tunFd = dup
	}

	engine.Insert(&engine.Key{
		Device:   fmt.Sprintf("fd://%d", tunFd),
		Proxy:    "socks5://" + VPNProxyAddr,
		LogLevel: "error",
		MTU:      mtu,
	})
	engine.Start()

	tunUseLegacy = true
	tunRunning = true
	return nil
}

// StopVPN stops the active VPN stack (gVisor or legacy tun2socks).
// Safe to call when not running.
func StopVPN() error {
	tunMu.Lock()
	defer tunMu.Unlock()

	if !tunRunning {
		return nil
	}

	if tunUseLegacy {
		engine.Stop()
	} else {
		// Close the dup'd fd BEFORE Wait(): the fdbased endpoint goroutine is
		// blocked inside BlockingRead(tunDupFd). Closing the fd interrupts the
		// syscall, the goroutine exits, and Wait() returns promptly.
		// If we close it after Wait() we get a deadlock.
		if tunDupFd >= 0 {
			unix.Close(tunDupFd)
			tunDupFd = -1
		}
		tunStack.Close()
		tunStack.Wait()
		tunStack = nil
	}

	resetVPNStats()

	tunRunning = false
	return nil
}

// gostTransportHandler implements adapter.TransportHandler.
// It routes every gVisor TCP/UDP session through a gost chain Router,
// eliminating the need for a separate SOCKS5 proxy listening port.
type gostTransportHandler struct {
	router *xchain.Router
}

func (h *gostTransportHandler) HandleTCP(conn adapter.TCPConn) {
	go func() {
		defer conn.Close()

		id := conn.ID()
		dst := net.JoinHostPort(id.LocalAddress.String(), strconv.Itoa(int(id.LocalPort)))
		n := atomic.AddInt64(&vpnTCPConns, 1)
		logVPN("[tcp#%d] dial %s", n, dst)

		upstream, err := h.router.Dial(context.Background(), "tcp", dst)
		if err != nil {
			atomic.AddInt64(&vpnFailedConns, 1)
			logVPN("[tcp#%d] dial %s failed: %v", n, dst, err)
			return
		}
		defer upstream.Close()
		logVPN("[tcp#%d] relaying %s", n, dst)

		relay(conn, upstream)
		logVPN("[tcp#%d] done %s", n, dst)
	}()
}

func (h *gostTransportHandler) HandleUDP(conn adapter.UDPConn) {
	go func() {
		defer conn.Close()

		id := conn.ID()
		dst := net.JoinHostPort(id.LocalAddress.String(), strconv.Itoa(int(id.LocalPort)))
		n := atomic.AddInt64(&vpnUDPConns, 1)
		logVPN("[udp#%d] dial %s", n, dst)

		// router.Dial("udp", ...) sends X-Gost-Protocol:udp via HTTP CONNECT chains
		// so the server wraps the connection as a UDP tunnel.
		upstream, err := h.router.Dial(context.Background(), "udp", dst)
		if err != nil {
			atomic.AddInt64(&vpnFailedConns, 1)
			logVPN("[udp#%d] dial %s failed: %v", n, dst, err)
			return
		}
		defer upstream.Close()
		logVPN("[udp#%d] relaying %s", n, dst)

		relay(conn, upstream)
	}()
}

// relay pipes data bidirectionally between src and dst. When one direction
// reaches EOF, CloseWrite is called on the other side so the remote peer
// receives a proper FIN and the other goroutine unblocks.
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

// closeWrite signals write-EOF on c if it supports half-close (e.g. TCP).
func closeWrite(c net.Conn) {
	type halfCloser interface {
		CloseWrite() error
	}
	if hc, ok := c.(halfCloser); ok {
		_ = hc.CloseWrite()
	}
}
