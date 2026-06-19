package stack

import (
	"context"
	"fmt"
	"net/netip"
	"sync"
	"time"
)

// NAT port allocation range. Ports below 10000 are reserved for
// ephemeral OS use; 65535 is the uint16 max.
const (
	natPortMin = 10000
	natPortMax = 65535
)

// natSessionTimeout is how long an idle TCP session stays in the NAT
// table before being reaped. Long enough to survive idle periods on
// long-lived connections (HTTP keepalive, SSH, websockets); short
// enough that abandoned sessions don't leak memory forever.
const natSessionTimeout = 30 * time.Minute

// natCleanupInterval is how often the cleanup goroutine runs.
const natCleanupInterval = 5 * time.Minute

// connKey is the composite key used to identify a connection in the NAT table.
// Using both source and destination prevents a reused source port from being
// misrouted to the stale destination of a previous connection.
type connKey struct {
	Source      netip.AddrPort
	Destination netip.AddrPort
}

// TCPNat maps (source, destination) → (nat port) for TCP connections,
// and provides reverse lookup for response packets.
type TCPNat struct {
	mu         sync.RWMutex
	addrMap    map[connKey]uint16
	portMap    map[uint16]*TCPSession
	nextPort   uint16
}

type TCPSession struct {
	Source      netip.AddrPort
	Destination netip.AddrPort
	LastActive  time.Time
}

func NewTCPNat(ctx context.Context) *TCPNat {
	nat := &TCPNat{
		addrMap:  make(map[connKey]uint16),
		portMap:  make(map[uint16]*TCPSession),
		nextPort: natPortMin,
	}
	go nat.loopCleanup(ctx)
	return nat
}

func (n *TCPNat) loopCleanup(ctx context.Context) {
	ticker := time.NewTicker(natCleanupInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			n.cleanExpired(natSessionTimeout)
		case <-ctx.Done():
			return
		}
	}
}

func (n *TCPNat) cleanExpired(maxAge time.Duration) {
	now := time.Now()
	n.mu.Lock()
	defer n.mu.Unlock()
	for port, sess := range n.portMap {
		if now.Sub(sess.LastActive) > maxAge {
			delete(n.addrMap, connKey{sess.Source, sess.Destination})
			delete(n.portMap, port)
		}
	}
}

// Lookup returns (or allocates) a NAT port for the given (source, destination)
// pair. Using both ends of the connection as the key ensures that source port
// reuse after TCP TIME_WAIT does not misroute a new connection to the stale
// destination of a previous one. If PrepareConnection is provided, it is
// called on first lookup.
func (n *TCPNat) Lookup(source, destination netip.AddrPort, prepare func() error) (uint16, error) {
	key := connKey{source, destination}
	n.mu.RLock()
	port, ok := n.addrMap[key]
	n.mu.RUnlock()
	if ok {
		return port, nil
	}
	if prepare != nil {
		if err := prepare(); err != nil {
			return 0, err
		}
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	// double-check after acquiring write lock
	if port, ok = n.addrMap[key]; ok {
		return port, nil
	}
	// Find a free port, skipping any that are in use. This handles
	// wraparound correctly: if nextPort wraps past 65535 back to 10000
	// and 10000 is still occupied by a long-lived session, we keep
	// scanning instead of overwriting the existing session.
	port = n.nextPort
	rangeSize := uint16(natPortMax - natPortMin + 1)
	for i := uint16(0); i < rangeSize; i++ {
		if _, inUse := n.portMap[port]; !inUse {
			n.nextPort = port + 1
			if n.nextPort < natPortMin || n.nextPort > natPortMax {
				n.nextPort = natPortMin
			}
			n.addrMap[key] = port
			n.portMap[port] = &TCPSession{
				Source:      source,
				Destination: destination,
				LastActive:  time.Now(),
			}
			return port, nil
		}
		port++
		if port < natPortMin || port > natPortMax {
			port = natPortMin
		}
	}
	return 0, fmt.Errorf("NAT port exhaustion: no free ports in [%d, %d]", natPortMin, natPortMax)
}

// LookupBack returns the session for a NAT port (used for response packets).
func (n *TCPNat) LookupBack(port uint16) *TCPSession {
	n.mu.RLock()
	sess := n.portMap[port]
	n.mu.RUnlock()
	if sess != nil {
		sess.LastActive = time.Now()
	}
	return sess
}
