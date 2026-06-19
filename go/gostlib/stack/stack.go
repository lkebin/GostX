package stack

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sirupsen/logrus"
)

// TunDevice reads/writes raw IP packets from a TUN file descriptor.
type TunDevice struct {
	file *os.File
	mtu  int
	addr netip.Prefix
}

func NewTunDevice(fd int, mtu int, addr netip.Prefix) (*TunDevice, error) {
	return &TunDevice{
		file: os.NewFile(uintptr(fd), "tun"),
		mtu:  mtu,
		addr: addr,
	}, nil
}

func (t *TunDevice) Read(p []byte) (int, error)  { return t.file.Read(p) }
func (t *TunDevice) Write(p []byte) (int, error) { return t.file.Write(p) }
func (t *TunDevice) Close() error                { return t.file.Close() }
func (t *TunDevice) MTU() int                    { return t.mtu }

// Stack implements a kernel-TCP-based TUN stack.
// TCP packets have their headers rewritten to redirect to a local listener;
// the kernel handles TCP state, so we get a clean net.Conn.
type Stack struct {
	ctx         context.Context
	cancel      context.CancelFunc
	tun         *TunDevice
	handler     Handler
	serverAddr  netip.Addr
	natAddr     netip.Addr
	tcpListener net.Listener
	tcpPort     uint16
	tcpNat      *TCPNat
	udpTimeout  time.Duration
	udpConns    map[connKey]*udpConn
	udpMu       sync.Mutex
	tcpRespOK      uint64
	tcpRespDropped uint64
	tcpDiagCount   uint64 // counts TCP packets for capped diagnostic logging
	// Forward-direction packet counters (app → listener).
	// tcpFwdSyn:  SYN packets that created a new NAT session.
	// tcpFwdData: data/ACK packets for an existing session, rewritten and
	//             written back to the TUN.
	// tcpFwdDrop: forward packets dropped (bad dst, prepare error, etc.).
	tcpFwdSyn  uint64
	tcpFwdData uint64
	tcpFwdDrop uint64
	tunWriteErr uint64
}

// NewStack creates a new system stack. The addr prefix must have room for
// at least 2 addresses: .Addr() is the server address (TCP listener,
// matches the TUN interface address), and .Addr().Next() is the NAT source
// address used when rewriting forward SYN packets. The NAT source must
// differ from vpnDNSVirtualAddr to avoid martian-source packet drops.
func NewStack(ctx context.Context, tun *TunDevice, handler Handler, udpTimeout time.Duration) (*Stack, error) {
	ctx, cancel := context.WithCancel(ctx)

	prefix := tun.addr
	if !prefix.IsValid() {
		cancel()
		return nil, fmt.Errorf("invalid tun address prefix")
	}
	serverAddr := prefix.Addr()
	natAddr := serverAddr.Next()
	if !prefix.Contains(natAddr) {
		cancel()
		return nil, fmt.Errorf("tun prefix %s too small (need room for 2 addresses)", prefix)
	}

	s := &Stack{
		ctx:        ctx,
		cancel:     cancel,
		tun:        tun,
		handler:    handler,
		serverAddr: serverAddr,
		natAddr:    natAddr,
		udpTimeout: udpTimeout,
		udpConns:   make(map[connKey]*udpConn),
	}
	s.tcpNat = NewTCPNat(ctx)
	go s.udpCleanupLoop(ctx, udpCleanupInterval)
	return s, nil
}

// udpCleanupInterval is how often the UDP cleanup goroutine scans for
// idle connections. This is intentionally shorter than udpTimeout so
// stale connections are reaped promptly.
const udpCleanupInterval = 30 * time.Second

// udpCleanupLoop periodically removes UDP connections that have been
// idle longer than s.udpTimeout. Also reaps connections whose handler
// has closed them but which are still in the map.
func (s *Stack) udpCleanupLoop(ctx context.Context, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			s.cleanupUDP(s.udpTimeout)
		case <-ctx.Done():
			return
		}
	}
}

// Start begins packet processing and starts the TCP listener.
func (s *Stack) Start() error {
	// Bind TCP listener to the server address. Port 0 picks an ephemeral port.
	listener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: s.serverAddr.AsSlice(), Port: 0})
	if err != nil {
		return fmt.Errorf("stack: listen TCP on %s: %w", s.serverAddr, err)
	}
	s.tcpListener = listener
	s.tcpPort = uint16(listener.Addr().(*net.TCPAddr).Port)
	logrus.Infof("stack: TCP listener on %s:%d", s.serverAddr, s.tcpPort)

	go s.acceptLoop()
	go s.tunLoop()
	go s.selfTest()
	return nil
}

// selfTest attempts a direct TCP dial to the listener to verify it is reachable
// from within the gost process, bypassing TUN routing entirely. If this
// succeeds but normal TUN-path connections don't, it confirms the Android
// routing issue: the gost process's kernel sockets bypass VPN routing (tun0),
// so SYN-ACKs go via the physical interface instead of TUN.
func (s *Stack) selfTest() {
	time.Sleep(200 * time.Millisecond)
	addr := fmt.Sprintf("%s:%d", s.serverAddr, s.tcpPort)
	conn, err := net.DialTimeout("tcp4", addr, 2*time.Second)
	if err != nil {
		logrus.Errorf("stack: self-test FAIL - direct dial to listener %s failed: %v", addr, err)
		return
	}
	logrus.Infof("stack: self-test OK - direct dial to listener succeeded (local=%v remote=%v)", conn.LocalAddr(), conn.RemoteAddr())
	conn.Close()
}

func (s *Stack) Close() error {
	s.cancel()
	if s.tcpListener != nil {
		s.tcpListener.Close()
	}
	return s.tun.Close()
}

func (s *Stack) ServerPort() uint16 { return s.tcpPort }

// tunLoop reads raw IP packets from the TUN device and processes them.
func (s *Stack) tunLoop() {
	buf := make([]byte, s.tun.mtu+40)
	var pkts, tcpNew, tcpResp, udp uint64
	for {
		n, err := s.tun.Read(buf)
		if err != nil {
			if s.ctx.Err() != nil {
				return
			}
			logrus.Errorf("stack: tun read: %v", err)
			continue
		}
		if n < IPv4HeaderLen {
			continue
		}
		pkts++
		packet := buf[:n]
		ip := IPv4Header(packet)
		if ip.Version() != 4 || ip.HeaderLen() < IPv4HeaderLen {
			continue
		}
		switch ip.Protocol() {
		case TCPProtocol:
			if s.isResponsePacket(ip) {
				tcpResp++
			} else {
				tcpNew++
			}
			// Diagnostic: log first 30 TCP packets to show src/dst
			if n := atomic.AddUint64(&s.tcpDiagCount, 1); n <= 30 {
				tcpHdr := TCPHeader(ip.Payload())
				src := netip.AddrPortFrom(ip.SrcAddr(), tcpHdr.SrcPort())
				dst := netip.AddrPortFrom(ip.DstAddr(), tcpHdr.DstPort())
				flags := tcpHdr.Flags()
				fromSrv := ip.SrcAddr() == s.serverAddr
				logrus.Debugf("stack: tcp pkt#%d src=%v dst=%v flags=0x%02x fromSrv=%v (serverAddr=%v tcpPort=%d)",
					n, src, dst, flags, fromSrv, s.serverAddr, s.tcpPort)
			}
		case UDPProtocol:
			udp++
		}
		if pkts%50 == 0 {
			respOK := atomic.LoadUint64(&s.tcpRespOK)
			respDrop := atomic.LoadUint64(&s.tcpRespDropped)
			fwdSyn := atomic.LoadUint64(&s.tcpFwdSyn)
			fwdData := atomic.LoadUint64(&s.tcpFwdData)
			fwdDrop := atomic.LoadUint64(&s.tcpFwdDrop)
			fwdWriteErr := atomic.LoadUint64(&s.tunWriteErr)
			logrus.Infof("stack: tun stats pkts=%d tcp_new=%d tcp_resp=%d(resp_ok=%d resp_drop=%d) tcp_fwd(syn=%d data=%d drop=%d write_err=%d) udp=%d",
				pkts, tcpNew, tcpResp, respOK, respDrop, fwdSyn, fwdData, fwdDrop, fwdWriteErr, udp)
		}
		writeBack := s.processIPv4(ip)
		if writeBack {
			if _, err := s.tun.Write(packet); err != nil {
				atomic.AddUint64(&s.tunWriteErr, 1)
				logrus.Warnf("stack: tun write: %v", err)
			}
		}
	}
}

// isResponsePacket reports whether ip is a TCP packet whose source matches
// the local listener (serverAddr:tcpPort). Used only for stats counting.
func (s *Stack) isResponsePacket(ip IPv4Header) bool {
	tcpHdr := TCPHeader(ip.Payload())
	if len(tcpHdr) < TCPHeaderMinLen {
		return false
	}
	return ip.SrcAddr() == s.serverAddr && tcpHdr.SrcPort() == s.tcpPort
}

func (s *Stack) processIPv4(ip IPv4Header) bool {
	// Reject packets that aren't IPv4. An IPv6 packet has version nibble 6
	// and IHL 0, which would make Payload() return the entire buffer and
	// cause garbage parses downstream (potentially polluting the NAT table
	// if byte 9 happens to equal TCPProtocol).
	if ip.Version() != 4 {
		logrus.Tracef("stack: drop non-IPv4 packet (version=%d)", ip.Version())
		return false
	}
	// Reject malformed headers (IHL too small to fit a standard IPv4 header).
	if ip.HeaderLen() < IPv4HeaderLen {
		logrus.Tracef("stack: drop IPv4 packet with bad header length %d", ip.HeaderLen())
		return false
	}

	proto := ip.Protocol()
	switch proto {
	case TCPProtocol:
		return s.processTCP(ip)
	case UDPProtocol:
		s.processUDP(ip)
		return false
	default:
		logrus.Tracef("stack: drop IPv4 packet with unsupported protocol %d", proto)
		return false
	}
}

// processTCP rewrites TCP packet headers for kernel TCP redirect.
//
// Direction 1 — new connection from app:
//
//	[app:port1 → google:443]  →  [natAddr:natPort → serverAddr:tcpPort]
//	Written back to TUN; kernel routes to local TCP listener.
//
// Direction 2 — response from kernel (listener → app):
//
//	[serverAddr:tcpPort → natAddr:natPort]  →  [google:443 → app:port1]
//	Written back to TUN; kernel routes to the app.
func (s *Stack) processTCP(ip IPv4Header) bool {
	tcpHdr := TCPHeader(ip.Payload())
	if len(tcpHdr) < TCPHeaderMinLen {
		return false
	}

	src := netip.AddrPortFrom(ip.SrcAddr(), tcpHdr.SrcPort())
	dst := netip.AddrPortFrom(ip.DstAddr(), tcpHdr.DstPort())

	// Response direction: packet from server address back to NAT address.
	if src.Addr() == s.serverAddr && src.Port() == s.tcpPort {
		sess := s.tcpNat.LookupBack(dst.Port())
		if sess == nil {
			atomic.AddUint64(&s.tcpRespDropped, 1)
			logrus.Tracef("stack: tcp: unknown nat port %d", dst.Port())
			return false
		}
		atomic.AddUint64(&s.tcpRespOK, 1)
		logrus.Debugf("stack: tcp RESP {%v→%v} nat:%d → rewrite {%v→%v}", src, dst, dst.Port(), sess.Destination, sess.Source)
		// Rewrite back: src = original dst, dst = original src
		ip.SetSrcAddr(sess.Destination.Addr())
		tcpHdr.SetSrcPort(sess.Destination.Port())
		ip.SetDstAddr(sess.Source.Addr())
		tcpHdr.SetDstPort(sess.Source.Port())
	} else {
		// New connection from app.
		if !dst.Addr().IsGlobalUnicast() && !dst.Addr().IsPrivate() {
			atomic.AddUint64(&s.tcpFwdDrop, 1)
			return false
		}
		isSYN := tcpHdr.Flags()&TCPFlagSYN != 0 && tcpHdr.Flags()&TCPFlagACK == 0
		natPort, err := s.tcpNat.Lookup(src, dst, func() error {
			return s.handler.PrepareConnection("tcp", src, dst)
		})
		if err != nil {
			atomic.AddUint64(&s.tcpFwdDrop, 1)
			logrus.Tracef("stack: tcp: drop %v -> %v: %v", src, dst, err)
			return false
		}
		if isSYN {
			atomic.AddUint64(&s.tcpFwdSyn, 1)
		} else {
			atomic.AddUint64(&s.tcpFwdData, 1)
		}
		// Rewrite: src = nat address, dst = server address
		ip.SetSrcAddr(s.natAddr)
		tcpHdr.SetSrcPort(natPort)
		ip.SetDstAddr(s.serverAddr)
		tcpHdr.SetDstPort(s.tcpPort)

		if isSYN {
			logrus.Debugf("stack: tcp SYN fwd %v→%v → nat:%d→srv:%v", src, dst, natPort, s.serverAddr)
		}
	}

	// Recalculate checksums — CalculateChecksum zeroes the field and returns ^sum.
	tcpHdr.SetChecksum(tcpHdr.CalculateChecksum(ip))
	ip.SetChecksum(ip.CalculateChecksum())
	return true
}

// acceptLoop accepts TCP connections from the local listener and routes them
// to the handler.
func (s *Stack) acceptLoop() {
	for {
		conn, err := s.tcpListener.Accept()
		if err != nil {
			if s.ctx.Err() != nil {
				return
			}
			logrus.Errorf("stack: accept: %v", err)
			continue
		}

		tcpConn := conn.(*net.TCPConn)
		remotePort := uint16(tcpConn.RemoteAddr().(*net.TCPAddr).Port)
		sess := s.tcpNat.LookupBack(remotePort)
		if sess == nil {
			logrus.Warnf("stack: accept: no NAT session for remote port %d (from %s) — kernel redirect source unexpected",
				remotePort, tcpConn.RemoteAddr())
			conn.Close()
			continue
		}
		logrus.Infof("stack: accept %s -> %s", sess.Source, sess.Destination)

		go s.handler.NewConnection(
			s.ctx,
			&tcpConnWrapper{conn: conn, src: sess.Source, dst: sess.Destination},
			sess.Source,
			sess.Destination,
		)
	}
}

type tcpConnWrapper struct {
	conn net.Conn
	src  netip.AddrPort
	dst  netip.AddrPort
}

func (w *tcpConnWrapper) Read(b []byte) (int, error)          { return w.conn.Read(b) }
func (w *tcpConnWrapper) Write(b []byte) (int, error)         { return w.conn.Write(b) }
func (w *tcpConnWrapper) Close() error                         { return w.conn.Close() }
func (w *tcpConnWrapper) LocalAddr() netip.AddrPort            { return w.dst }
func (w *tcpConnWrapper) RemoteAddr() netip.AddrPort           { return w.src }

// CloseWrite signals write-EOF on the underlying TCP connection so the
// peer receives a proper FIN. The underlying conn is always a *net.TCPConn
// (from the local listener), so the type assertion always succeeds in
// practice.
func (w *tcpConnWrapper) CloseWrite() error {
	if hc, ok := w.conn.(interface{ CloseWrite() error }); ok {
		return hc.CloseWrite()
	}
	return nil
}

// processUDP handles UDP packets from the TUN.
func (s *Stack) processUDP(ip IPv4Header) {
	udpHdr := UDPHeader(ip.Payload())
	if len(udpHdr) < UDPHeaderLen {
		return
	}
	src := netip.AddrPortFrom(ip.SrcAddr(), udpHdr.SrcPort())
	dst := netip.AddrPortFrom(ip.DstAddr(), udpHdr.DstPort())
	if !dst.Addr().IsGlobalUnicast() && !dst.Addr().IsPrivate() {
		return
	}

	ucKey := connKey{src, dst}
	s.udpMu.Lock()
	uc, ok := s.udpConns[ucKey]
	if !ok {
		uc = &udpConn{
			stack:       s,
			src:         src,
			dst:         dst,
			packetCh:    make(chan *udpPacket, 64),
			closeCh:     make(chan struct{}),
			lastActive:  time.Now(),
		}
		s.udpConns[ucKey] = uc
		go s.handler.NewPacketConnection(s.ctx, uc, src, dst)
	}
	uc.lastActive = time.Now()
	s.udpMu.Unlock()

	// Copy the payload and enqueue
	payload := make([]byte, len(udpHdr.Payload()))
	copy(payload, udpHdr.Payload())

	select {
	case uc.packetCh <- &udpPacket{data: payload, from: dst}:
	default:
		logrus.Tracef("stack: udp: drop packet from %v (queue full)", src)
	}
}

type udpPacket struct {
	data []byte
	from netip.AddrPort
}

type udpConn struct {
	stack      *Stack
	src        netip.AddrPort
	dst        netip.AddrPort
	packetCh   chan *udpPacket
	closeCh    chan struct{}
	lastActive time.Time
	mu         sync.Mutex
	closed     bool
}

func (u *udpConn) ReadPacket() ([]byte, netip.AddrPort, error) {
	select {
	case pkt := <-u.packetCh:
		return pkt.data, pkt.from, nil
	case <-u.closeCh:
		return nil, netip.AddrPort{}, net.ErrClosed
	}
}

func (u *udpConn) WritePacket(data []byte) error {
	u.mu.Lock()
	if u.closed {
		u.mu.Unlock()
		return net.ErrClosed
	}
	u.mu.Unlock()

	// Build IP+UDP packet: [IP header][UDP header][payload]
	totalLen := IPv4HeaderLen + UDPHeaderLen + len(data)
	buf := NewBuffer()
	defer FreeBuffer(buf)
	packet := buf[:totalLen]

	ip := IPv4Header(packet)
	// Response packet: src = original destination (e.g., 8.8.8.8:53),
	// dst = original source (the app). The app's socket expects replies
	// from the address it sent to; using serverAddr here would cause
	// connected UDP sockets (e.g., DNS resolvers) to reject the packet.
	ip.Encode(uint16(totalLen), UDPProtocol, u.dst.Addr(), u.src.Addr())

	udp := UDPHeader(ip.Payload())
	udp.SetSrcPort(u.dst.Port())
	udp.SetDstPort(u.src.Port())
	udp.SetLength(uint16(UDPHeaderLen + len(data)))
	copy(udp.Payload(), data)

	udp.SetChecksum(0) // must zero before computing (tcpUDPChecksum reads the field)
	cs := ^tcpUDPChecksum(ip, UDPProtocol)
	if cs == 0 {
		// RFC 768: a stored checksum of 0 means "checksum disabled".
		// When the computed ones-complement happens to be 0, use 0xFFFF.
		cs = 0xFFFF
	}
	udp.SetChecksum(cs)
	ip.SetChecksum(ip.CalculateChecksum())

	_, err := u.stack.tun.Write(packet)
	return err
}

func (u *udpConn) Close() error {
	u.mu.Lock()
	if u.closed {
		u.mu.Unlock()
		return nil
	}
	u.closed = true
	close(u.closeCh)
	u.mu.Unlock()

	// Remove from stack's map so future packets from this source allocate
	// a fresh udpConn instead of being silently dropped on a dead one.
	// Acquired after u.mu is released to avoid nested-lock ordering with
	// processUDP (which takes s.udpMu first).
	u.stack.udpMu.Lock()
	// Only delete if the map still points at us — a concurrent cleanupUDP
	// may have already swapped us out.
	if cur, ok := u.stack.udpConns[connKey{u.src, u.dst}]; ok && cur == u {
		delete(u.stack.udpConns, connKey{u.src, u.dst})
	}
	u.stack.udpMu.Unlock()
	return nil
}

// cleanupUDP removes UDP connections that are either closed or have been
// idle longer than maxAge. Called periodically by udpCleanupLoop.
func (s *Stack) cleanupUDP(maxAge time.Duration) {
	s.udpMu.Lock()
	now := time.Now()
	var toClose []*udpConn
	for key, uc := range s.udpConns {
		uc.mu.Lock()
		closed := uc.closed
		uc.mu.Unlock()
		if closed || now.Sub(uc.lastActive) > maxAge {
			delete(s.udpConns, key)
			toClose = append(toClose, uc)
		}
	}
	s.udpMu.Unlock()

	// Close outside the lock — uc.Close() acquires s.udpMu to remove itself
	// from the map, which would deadlock if we held it here.
	for _, uc := range toClose {
		uc.Close()
	}
}
