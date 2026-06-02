package gostlib

import (
	"context"
	"fmt"
	"io"
	"net"
	"sync"

	"golang.org/x/sys/unix"

	gostchain "github.com/go-gost/core/chain"
	xchain "github.com/go-gost/x/chain"
	"github.com/go-gost/x/registry"
)

var (
	tunMu      sync.Mutex
	tunRunning bool
	tunEngine  *mixedEngine
)

// StartVPN starts VPN packet routing in mixed mode.
//
// TCP is handled via kernel DNAT (efficient), UDP via a minimal gVisor
// userspace stack (correct session tracking), ICMP Echo inline.
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
	dnsServiceAddr := vpnDNSServiceAddr
	mu.Unlock()

	// Dup the fd so the mixed engine owns an independent copy.
	dupFd, err := unix.Dup(fd)
	if err != nil {
		return fmt.Errorf("dup TUN fd: %w", err)
	}

	// Resolve the chain from gost's registry.
	var chainer gostchain.Chainer
	if chainName != "" {
		chainer = registry.ChainRegistry().Get(chainName)
		if chainer == nil {
			logVPN("[warn] chain %q not found in registry – traffic will route directly", chainName)
		} else {
			logVPN("[info] chain %q found, mixed engine starting (fd=%d mtu=%d)", chainName, fd, mtu)
		}
	} else {
		logVPN("[info] no chain name – mixed engine will route directly (fd=%d mtu=%d)", fd, mtu)
	}
	router := xchain.NewRouter(gostchain.ChainRouterOption(chainer))

	engine, err := newMixedEngine(dupFd, mtu, router, dnsServiceAddr)
	if err != nil {
		unix.Close(dupFd)
		return fmt.Errorf("create mixed engine: %w", err)
	}

	// Start the packet processing loop in the background.
	go func() {
		if err := engine.run(); err != nil && err != context.Canceled {
			logVPN("[engine] error: %v", err)
		}
	}()

	tunEngine = engine
	tunRunning = true
	return nil
}

// StopVPN stops the mixed mode VPN engine.
// Safe to call when not running.
func StopVPN() error {
	tunMu.Lock()
	defer tunMu.Unlock()

	if !tunRunning {
		return nil
	}

	// engine.Close() closes the dup'd TUN fd and all goroutines.
	tunEngine.Close()
	tunEngine = nil

	resetVPNStats()
	tunRunning = false
	return nil
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
