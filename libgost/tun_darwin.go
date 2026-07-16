//go:build darwin

package libgost

import (
	"golang.org/x/sys/unix"
)

// GetTunnelFileDescriptor scans file descriptors 0..1024 for the utun
// control socket (com.apple.net.utun_control) and returns its fd.
// Returns -1 if no utun socket is found.
//
// Implementation adapted from wireguard-apple's WireGuardAdapter.tunnelFileDescriptor.
func GetTunnelFileDescriptor() int32 {
	ctlInfo := &unix.CtlInfo{}
	copy(ctlInfo.Name[:], "com.apple.net.utun_control")
	for fd := int32(0); fd < 1024; fd++ {
		addr, err := unix.Getpeername(int(fd))
		if err != nil {
			continue
		}
		addrCTL, ok := addr.(*unix.SockaddrCtl)
		if !ok {
			continue
		}
		if ctlInfo.Id == 0 {
			if err = unix.IoctlCtlInfo(int(fd), ctlInfo); err != nil {
				continue
			}
		}
		if addrCTL.ID == ctlInfo.Id {
			return fd
		}
	}
	return -1
}
