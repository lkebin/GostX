package gostlib

import (
	"fmt"
	"strings"
	"sync/atomic"
)

// vpnLogCh buffers log messages from the gVisor transport handler.
// Capacity 512 means we can hold ~512 messages before dropping.
var vpnLogCh = make(chan string, 512)

// VPN connection counters; reset by resetVPNStats.
var (
	vpnTCPConns    int64 // total TCP sessions dispatched
	vpnUDPConns    int64 // total UDP sessions dispatched
	vpnFailedConns int64 // sessions where router.Dial failed
)

func logVPN(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	select {
	case vpnLogCh <- msg:
	default:
		// buffer full – drop to avoid blocking the packet-dispatch goroutine
	}
}

// GetVPNLog drains all pending VPN log messages and returns them
// newline-separated. Call periodically (e.g., every second) to stream
// transport-level events to the app's log UI.
func GetVPNLog() string {
	var sb strings.Builder
	for {
		select {
		case msg := <-vpnLogCh:
			sb.WriteString(msg)
			sb.WriteByte('\n')
		default:
			return sb.String()
		}
	}
}

func resetVPNStats() {
	atomic.StoreInt64(&vpnTCPConns, 0)
	atomic.StoreInt64(&vpnUDPConns, 0)
	atomic.StoreInt64(&vpnFailedConns, 0)
	// drain any stale log messages from a previous session
	for {
		select {
		case <-vpnLogCh:
		default:
			return
		}
	}
}
