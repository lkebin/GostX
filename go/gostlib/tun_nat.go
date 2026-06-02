package gostlib

import (
	"fmt"
	"net"
	"sync"
	"time"
)

const (
	natPortMin = 9000
	natPortMax = 65000
	natGCEvery = 30 * time.Second
	natTTL     = 2 * time.Minute
)

type natEntry struct {
	src      net.TCPAddr
	dst      net.TCPAddr
	lastUsed time.Time
}

type tunNATTable struct {
	mu       sync.RWMutex
	entries  map[uint16]*natEntry // localPort -> entry
	byKey    map[string]uint16    // "srcIP:srcPort->dstIP:dstPort" -> localPort
	nextPort uint16
	closeCh  chan struct{}
}

func newNATTable() *tunNATTable {
	t := &tunNATTable{
		entries:  make(map[uint16]*natEntry),
		byKey:    make(map[string]uint16),
		nextPort: natPortMin,
		closeCh:  make(chan struct{}),
	}
	go t.gcLoop()
	return t
}

func natKey(src, dst net.TCPAddr) string {
	return fmt.Sprintf("%s:%d->%s:%d", src.IP, src.Port, dst.IP, dst.Port)
}

func (t *tunNATTable) CreateOrLookup(src, dst net.TCPAddr) uint16 {
	t.mu.Lock()
	defer t.mu.Unlock()

	key := natKey(src, dst)
	if port, ok := t.byKey[key]; ok {
		if entry := t.entries[port]; entry != nil {
			entry.lastUsed = time.Now()
			return port
		}
		// Stale byKey entry (should not happen) — clean up and reallocate.
		delete(t.byKey, key)
	}

	// Probe forward from nextPort to find a free slot.
	// Under normal GC load (30s sweep, 2min TTL) with 56k available ports,
	// an empty slot is found within a few iterations. If every port is occupied
	// (extreme load), we log a warning and reuse the probed port anyway.
	port := t.nextPort
	for {
		if _, exists := t.entries[port]; !exists {
			break
		}
		port++
		if port > natPortMax {
			port = natPortMin
		}
		if port == t.nextPort {
			// Full circle — all ports in use; GC must catch up.
			logVPN("[warn] NAT table exhausted, reusing port %d", port)
			break
		}
	}
	t.nextPort = port + 1
	if t.nextPort > natPortMax {
		t.nextPort = natPortMin
	}

	t.entries[port] = &natEntry{src: src, dst: dst, lastUsed: time.Now()}
	t.byKey[key] = port
	return port
}

func (t *tunNATTable) ReverseLookup(port uint16) (net.TCPAddr, net.TCPAddr, bool) {
	t.mu.Lock()
	defer t.mu.Unlock()

	entry, ok := t.entries[port]
	if !ok {
		return net.TCPAddr{}, net.TCPAddr{}, false
	}
	entry.lastUsed = time.Now()
	return entry.src, entry.dst, true
}

func (t *tunNATTable) Remove(port uint16) {
	t.mu.Lock()
	defer t.mu.Unlock()

	entry, ok := t.entries[port]
	if !ok {
		return
	}
	key := natKey(entry.src, entry.dst)
	delete(t.byKey, key)
	delete(t.entries, port)
}

func (t *tunNATTable) Close() {
	close(t.closeCh)
}

func (t *tunNATTable) gcLoop() {
	ticker := time.NewTicker(natGCEvery)
	defer ticker.Stop()
	for {
		select {
		case <-t.closeCh:
			return
		case now := <-ticker.C:
			t.collectStale(now)
		}
	}
}

func (t *tunNATTable) collectStale(now time.Time) {
	t.mu.Lock()
	defer t.mu.Unlock()

	deadline := now.Add(-natTTL)
	for port, entry := range t.entries {
		if entry.lastUsed.Before(deadline) {
			key := natKey(entry.src, entry.dst)
			delete(t.byKey, key)
			delete(t.entries, port)
		}
	}
}
