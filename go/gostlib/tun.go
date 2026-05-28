package gostlib

import (
	"fmt"
	"sync"

	"golang.org/x/sys/unix"

	"github.com/xjasonlyu/tun2socks/v2/engine"
)

var (
	tunMu      sync.Mutex
	tunRunning bool
)

// StartVPN starts tun2socks, routing all device traffic through gost's
// internal SOCKS5 listener. fd is the TUN file descriptor obtained from
// Android VpnService.Builder.establish() or iOS NEPacketTunnelProvider.
// Call StartVPNMode() successfully before calling StartVPN().
func StartVPN(fd int, mtu int) error {
	tunMu.Lock()
	defer tunMu.Unlock()

	if tunRunning {
		return fmt.Errorf("tun2socks already running; call StopVPN() first")
	}
	if fd < 0 {
		return fmt.Errorf("invalid TUN file descriptor: %d", fd)
	}
	if _, err := unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0); err != nil {
		return fmt.Errorf("invalid TUN file descriptor: %d: %w", fd, err)
	}

	// Dup the fd so tun2socks can close its own copy independently without
	// invalidating Android's ParcelFileDescriptor. FD.Close() calls unix.Close
	// on the fd it holds; using a dup prevents double-close of the original fd.
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

	tunRunning = true
	return nil
}

// StopVPN stops tun2socks. Safe to call when not running.
func StopVPN() error {
	tunMu.Lock()
	defer tunMu.Unlock()

	if !tunRunning {
		return nil
	}

	engine.Stop()
	tunRunning = false
	return nil
}
