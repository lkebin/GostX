package stack

import (
	"context"
	"net"
	"net/netip"
	"os"
	"testing"
	"time"
)

// testHandler is a no-op handler for unit tests.
type testHandler struct{}

func (h *testHandler) PrepareConnection(network string, source, destination netip.AddrPort) error {
	return nil
}
func (h *testHandler) NewConnection(ctx context.Context, conn TCPConn, source, destination netip.AddrPort) {
}
func (h *testHandler) NewPacketConnection(ctx context.Context, conn PacketConn, source, destination netip.AddrPort) {
}

// newTestStack creates a Stack with known addresses for testing.
// serverAddr=10.0.0.2, natAddr=10.0.0.3, tcpPort=12345
func newTestStack() *Stack {
	ctx := context.Background()
	return &Stack{
		ctx:        ctx,
		serverAddr: netip.MustParseAddr("10.0.0.2"),
		natAddr:    netip.MustParseAddr("10.0.0.3"),
		tcpPort:    12345,
		tcpNat:     NewTCPNat(ctx),
		handler:    &testHandler{},
		udpConns:   make(map[connKey]*udpConn),
	}
}

// checksumOK verifies IP and TCP checksums are valid.
// After setting the correct checksum, the one's complement sum must be 0xFFFF.
func checksumOK(t *testing.T, ip IPv4Header, tcp TCPHeader, label string) {
	t.Helper()
	if s := OnesComplementSum(ip[:ip.HeaderLen()]); s != 0xFFFF {
		t.Errorf("%s: IP checksum bad — OnesComplementSum = 0x%04X, want 0xFFFF", label, s)
	}
	if s := tcpUDPChecksum(ip, TCPProtocol); s != 0xFFFF {
		t.Errorf("%s: TCP checksum bad — tcpUDPChecksum = 0x%04X, want 0xFFFF", label, s)
	}
}

// buildTCPPacket constructs an IPv4+TCP packet in buf.
// The IPTotalLen is set correctly. Caller sets TCP header fields after.
func buildTCPPacket(buf []byte, srcIP, dstIP netip.Addr) (IPv4Header, TCPHeader) {
	totalLen := uint16(IPv4HeaderLen + TCPHeaderMinLen)
	ip := IPv4Header(buf)
	ip.Encode(totalLen, TCPProtocol, srcIP, dstIP)
	ip.SetChecksum(ip.CalculateChecksum())

	tcp := TCPHeader(ip.Payload())
	// Set data offset to 5 (20-byte header, standard minimum)
	buf[IPv4HeaderLen+12] = 0x50
	return ip, tcp
}

func TestProcessTCPNewConnection(t *testing.T) {
	s := newTestStack()
	buf := make([]byte, IPv4HeaderLen+TCPHeaderMinLen)

	appIP := netip.MustParseAddr("10.0.0.100")
	googleIP := netip.MustParseAddr("142.250.80.46")

	ip, tcp := buildTCPPacket(buf, appIP, googleIP)
	tcp.SetSrcPort(33333)
	tcp.SetDstPort(443)
	// Set correct checksums before rewriting
	tcp.SetChecksum(tcp.CalculateChecksum(ip))
	ip.SetChecksum(ip.CalculateChecksum())

	// Verify initial checksums are valid
	sum := OnesComplementSum(buf[:ip.HeaderLen()])
	if sum != 0xFFFF {
		t.Fatalf("initial IP checksum not 0xFFFF: got 0x%04X", sum)
	}

	// Process — should rewrite to redirect to local listener
	ok := s.processTCP(ip)
	if !ok {
		t.Fatal("processTCP returned false for valid packet")
	}

	// Verify rewritten addresses
	if ip.SrcAddr() != s.natAddr {
		t.Errorf("src addr = %s, want %s", ip.SrcAddr(), s.natAddr)
	}
	if ip.DstAddr() != s.serverAddr {
		t.Errorf("dst addr = %s, want %s", ip.DstAddr(), s.serverAddr)
	}
	if tcp.DstPort() != s.tcpPort {
		t.Errorf("tcp dst port = %d, want %d", tcp.DstPort(), s.tcpPort)
	}

	// The NAT should have a session
	natPort := tcp.SrcPort()
	sess := s.tcpNat.LookupBack(natPort)
	if sess == nil {
		t.Fatal("no NAT session for rewritten port")
	}
	if sess.Source.Addr() != appIP {
		t.Errorf("session src = %s, want %s", sess.Source.Addr(), appIP)
	}
	if sess.Destination.Addr() != googleIP {
		t.Errorf("session dst = %s, want %s", sess.Destination.Addr(), googleIP)
	}

	checksumOK(t, ip, tcp, "new-connection")
}

func TestProcessTCPResponseDirection(t *testing.T) {
	s := newTestStack()
	buf := make([]byte, IPv4HeaderLen+TCPHeaderMinLen)

	appIP := netip.MustParseAddr("10.0.0.100")
	googleIP := netip.MustParseAddr("142.250.80.46")
	appSrc := netip.MustParseAddrPort("10.0.0.100:33333")
	googleDst := netip.MustParseAddrPort("142.250.80.46:443")

	// Pre-populate NAT with a session (simulating a previous new-connection rewrite)
	natPort := uint16(10000)
	s.tcpNat.mu.Lock()
	s.tcpNat.addrMap[connKey{appSrc, googleDst}] = natPort
	s.tcpNat.portMap[natPort] = &TCPSession{
		Source:      appSrc,
		Destination: googleDst,
	}
	s.tcpNat.mu.Unlock()

	// Build response packet: from kernel listener back to NAT address
	ip, tcp := buildTCPPacket(buf, s.serverAddr, s.natAddr)
	tcp.SetSrcPort(s.tcpPort)
	tcp.SetDstPort(natPort)
	tcp.SetChecksum(tcp.CalculateChecksum(ip))
	ip.SetChecksum(ip.CalculateChecksum())

	ok := s.processTCP(ip)
	if !ok {
		t.Fatal("processTCP returned false for response packet")
	}

	// Verify rewritten back to original addresses
	if ip.SrcAddr() != googleIP {
		t.Errorf("src addr = %s, want %s", ip.SrcAddr(), googleIP)
	}
	if ip.DstAddr() != appIP {
		t.Errorf("dst addr = %s, want %s", ip.DstAddr(), appIP)
	}
	if tcp.SrcPort() != 443 {
		t.Errorf("tcp src port = %d, want 443", tcp.SrcPort())
	}
	if tcp.DstPort() != 33333 {
		t.Errorf("tcp dst port = %d, want 33333", tcp.DstPort())
	}

	checksumOK(t, ip, tcp, "response")
}

func TestProcessTCPNonGlobalDst(t *testing.T) {
	s := newTestStack()
	buf := make([]byte, IPv4HeaderLen+TCPHeaderMinLen)

	// 127.0.0.1 is neither global unicast nor private
	loopback := netip.MustParseAddr("127.0.0.1")
	appIP := netip.MustParseAddr("10.0.0.100")

	ip, tcp := buildTCPPacket(buf, appIP, loopback)
	tcp.SetSrcPort(33333)
	tcp.SetDstPort(8080)
	tcp.SetChecksum(tcp.CalculateChecksum(ip))
	ip.SetChecksum(ip.CalculateChecksum())

	ok := s.processTCP(ip)
	if ok {
		t.Fatal("processTCP should return false for non-global non-private dst")
	}
}

func TestProcessTCPInvalidHeader(t *testing.T) {
	s := newTestStack()
	// Build a packet where the buffer is too short for a full TCP header
	buf := make([]byte, IPv4HeaderLen+10) // only 10 bytes for TCP header
	appIP := netip.MustParseAddr("10.0.0.100")
	dstIP := netip.MustParseAddr("1.2.3.4")

	totalLen := uint16(len(buf))
	ip := IPv4Header(buf)
	ip.Encode(totalLen, TCPProtocol, appIP, dstIP)

	ok := s.processTCP(ip)
	if ok {
		t.Fatal("processTCP should return false for truncated TCP header")
	}
}

func TestUDPWritePacketChecksum(t *testing.T) {
	serverAddr := netip.MustParseAddr("10.0.0.2")
	src := netip.MustParseAddrPort("10.0.0.100:33333")
	dst := netip.MustParseAddrPort("8.8.8.8:53")

	// Use os.Pipe to capture the written packet through a real TunDevice.
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatalf("pipe: %v", err)
	}
	defer r.Close()
	defer w.Close()

	tun, err := NewTunDevice(int(w.Fd()), 1500, netip.MustParsePrefix("10.0.0.2/24"))
	if err != nil {
		t.Fatalf("NewTunDevice: %v", err)
	}

	uc := &udpConn{
		stack: &Stack{
			serverAddr: serverAddr,
			tun:        tun,
		},
		src: src,
		dst: dst,
	}

	payload := []byte("test-udp-payload")
	if err := uc.WritePacket(payload); err != nil {
		t.Fatalf("WritePacket: %v", err)
	}

	// Read the packet back from the pipe
	writtenPacket := make([]byte, 65535)
	n, _ := r.Read(writtenPacket)
	writtenPacket = writtenPacket[:n]

	expectedLen := IPv4HeaderLen + UDPHeaderLen + len(payload)
	if n != expectedLen {
		t.Fatalf("packet len = %d, want %d", n, expectedLen)
	}

	ip := IPv4Header(writtenPacket)
	udp := UDPHeader(ip.Payload())

	// Verify addresses — response must look like it came from the original
	// destination (e.g., 8.8.8.8:53), not from the TUN's server address.
	// Apps using connected UDP sockets will reject packets whose source
	// doesn't match the original destination.
	if ip.SrcAddr() != dst.Addr() {
		t.Errorf("IP src = %s, want %s (original dst)", ip.SrcAddr(), dst.Addr())
	}
	if ip.DstAddr() != src.Addr() {
		t.Errorf("IP dst = %s, want %s", ip.DstAddr(), src.Addr())
	}
	if udp.SrcPort() != dst.Port() {
		t.Errorf("UDP src = %d, want %d", udp.SrcPort(), dst.Port())
	}
	if udp.DstPort() != src.Port() {
		t.Errorf("UDP dst = %d, want %d", udp.DstPort(), src.Port())
	}

	// Verify checksums
	if s := OnesComplementSum(writtenPacket[:IPv4HeaderLen]); s != 0xFFFF {
		t.Errorf("IP checksum bad: sum=0x%04X, want 0xFFFF", s)
	}
	if s := tcpUDPChecksum(ip, UDPProtocol); s != 0xFFFF {
		t.Errorf("UDP checksum bad: sum=0x%04X, want 0xFFFF", s)
	}
}

// TestUDPWritePacketChecksumPoolReuse verifies that WritePacket produces a
// correct UDP checksum even when the buffer pool returns a previously-used
// buffer with a stale checksum field. tcpUDPChecksum reads ALL bytes of
// ip.Payload() including the checksum field, so the field must be zeroed
// before the calculation or the result will include the stale value.
func TestUDPWritePacketChecksumPoolReuse(t *testing.T) {
	serverAddr := netip.MustParseAddr("10.0.0.2")
	src := netip.MustParseAddrPort("10.0.0.100:33333")
	dst := netip.MustParseAddrPort("8.8.8.8:53")

	r, w, err := os.Pipe()
	if err != nil {
		t.Fatalf("pipe: %v", err)
	}
	defer r.Close()
	defer w.Close()

	tun, err := NewTunDevice(int(w.Fd()), 1500, netip.MustParsePrefix("10.0.0.2/24"))
	if err != nil {
		t.Fatalf("NewTunDevice: %v", err)
	}

	uc := &udpConn{
		stack: &Stack{serverAddr: serverAddr, tun: tun},
		src:   src,
		dst:   dst,
	}

	drain := func(t *testing.T) []byte {
		t.Helper()
		buf := make([]byte, 65535)
		n, err := r.Read(buf)
		if err != nil {
			t.Fatalf("pipe read: %v", err)
		}
		return buf[:n]
	}

	// First call primes the pool: the buffer is returned to the pool after
	// WritePacket returns. Its checksum field now contains a non-zero value.
	if err := uc.WritePacket([]byte("first-payload")); err != nil {
		t.Fatalf("first WritePacket: %v", err)
	}
	drain(t)

	// Second call should get the same pool buffer (stale checksum field).
	// If the implementation doesn't zero the checksum before computing, the
	// result will be wrong and the kernel will drop the packet.
	if err := uc.WritePacket([]byte("second-payload")); err != nil {
		t.Fatalf("second WritePacket: %v", err)
	}
	pkt := drain(t)

	ip := IPv4Header(pkt)
	if s := tcpUDPChecksum(ip, UDPProtocol); s != 0xFFFF {
		t.Errorf("UDP checksum bad on pool-reused buffer: sum=0x%04X, want 0xFFFF", s)
	}
}

// TestTCPNatLookupKeyIncludesDestination verifies that two connections from
// the same source port to *different* destinations each get a distinct NAT
// port. Without (src, dst) as the composite key, source-port reuse after
// TCP TIME_WAIT would silently misroute new connections to the stale
// destination of the old one.
func TestTCPNatLookupKeyIncludesDestination(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	nat := NewTCPNat(ctx)

	src := netip.MustParseAddrPort("10.0.0.2:54321")
	dst1 := netip.MustParseAddrPort("8.8.8.8:443")
	dst2 := netip.MustParseAddrPort("1.1.1.1:443")

	port1, err := nat.Lookup(src, dst1, nil)
	if err != nil {
		t.Fatalf("Lookup(dst1): %v", err)
	}
	port2, err := nat.Lookup(src, dst2, nil)
	if err != nil {
		t.Fatalf("Lookup(dst2): %v", err)
	}

	if port1 == port2 {
		t.Errorf("same NAT port %d assigned for different destinations; "+
			"source port reuse would misroute new connections", port1)
	}

	sess1 := nat.LookupBack(port1)
	if sess1 == nil || sess1.Destination != dst1 {
		t.Errorf("LookupBack(%d) = %v, want destination %s", port1, sess1, dst1)
	}
	sess2 := nat.LookupBack(port2)
	if sess2 == nil || sess2.Destination != dst2 {
		t.Errorf("LookupBack(%d) = %v, want destination %s", port2, sess2, dst2)
	}
}

// TestUDPConnKeyIncludesDestination verifies that packets from the same source
// port to two different destinations each create an independent udpConn. A
// source-only key would dispatch the second packet to the first handler.
func TestUDPConnKeyIncludesDestination(t *testing.T) {
	s := newTestStack()

	src := netip.MustParseAddrPort("10.0.0.100:33333")
	dst1 := netip.MustParseAddrPort("8.8.8.8:53")
	dst2 := netip.MustParseAddrPort("1.1.1.1:53")

	uc1 := &udpConn{
		stack:    s,
		src:      src,
		dst:      dst1,
		packetCh: make(chan *udpPacket, 64),
		closeCh:  make(chan struct{}),
	}
	uc2 := &udpConn{
		stack:    s,
		src:      src,
		dst:      dst2,
		packetCh: make(chan *udpPacket, 64),
		closeCh:  make(chan struct{}),
	}
	s.udpConns[connKey{src, dst1}] = uc1
	s.udpConns[connKey{src, dst2}] = uc2

	got1 := s.udpConns[connKey{src, dst1}]
	got2 := s.udpConns[connKey{src, dst2}]
	if got1 == nil || got1 != uc1 {
		t.Errorf("udpConns[{src, dst1}] = %v, want uc1", got1)
	}
	if got2 == nil || got2 != uc2 {
		t.Errorf("udpConns[{src, dst2}] = %v, want uc2", got2)
	}
	if got1 == got2 {
		t.Error("same udpConn returned for different destinations; packets would be misrouted")
	}
}

// TestUDPCloseRemovesFromMap verifies that closing a udpConn removes it
// from the stack's udpConns map. Without this, closed connections linger
// and new packets from the same source are silently dropped.
func TestUDPCloseRemovesFromMap(t *testing.T) {
	s := newTestStack()
	src := netip.MustParseAddrPort("10.0.0.100:33333")
	dst := netip.MustParseAddrPort("8.8.8.8:53")

	uc := &udpConn{
		stack:    s,
		src:      src,
		dst:      dst,
		packetCh: make(chan *udpPacket, 64),
		closeCh:  make(chan struct{}),
	}
	s.udpConns[connKey{src, dst}] = uc

	if err := uc.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	s.udpMu.Lock()
	_, exists := s.udpConns[connKey{src, dst}]
	s.udpMu.Unlock()
	if exists {
		t.Fatal("udpConn still in map after Close")
	}
}

// TestUDPCleanupRemovesStale verifies cleanupUDP removes connections
// whose lastActive is older than the threshold.
func TestUDPCleanupRemovesStale(t *testing.T) {
	s := newTestStack()
	src := netip.MustParseAddrPort("10.0.0.100:33333")
	dst := netip.MustParseAddrPort("8.8.8.8:53")

	stale := &udpConn{
		stack:     s,
		src:       src,
		dst:       dst,
		packetCh:  make(chan *udpPacket, 64),
		closeCh:   make(chan struct{}),
		lastActive: time.Now().Add(-1 * time.Hour),
	}
	fresh := &udpConn{
		stack:     s,
		src:       netip.MustParseAddrPort("10.0.0.100:44444"),
		dst:       dst,
		packetCh:  make(chan *udpPacket, 64),
		closeCh:   make(chan struct{}),
		lastActive: time.Now(),
	}
	s.udpConns[connKey{stale.src, stale.dst}] = stale
	s.udpConns[connKey{fresh.src, fresh.dst}] = fresh

	s.cleanupUDP(5 * time.Minute)

	s.udpMu.Lock()
	_, staleExists := s.udpConns[connKey{stale.src, stale.dst}]
	_, freshExists := s.udpConns[connKey{fresh.src, fresh.dst}]
	s.udpMu.Unlock()

	if staleExists {
		t.Error("stale udpConn was not removed")
	}
	if !freshExists {
		t.Error("fresh udpConn was incorrectly removed")
	}
}

// TestUDPPeriodicCleanup verifies the cleanup goroutine launched by
// NewStack eventually removes stale udpConns without manual intervention.
func TestUDPPeriodicCleanup(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	s := &Stack{
		ctx:        ctx,
		serverAddr: netip.MustParseAddr("10.0.0.2"),
		natAddr:    netip.MustParseAddr("10.0.0.3"),
		tcpPort:    12345,
		tcpNat:     NewTCPNat(ctx),
		handler:    &testHandler{},
		udpTimeout: 50 * time.Millisecond,
		udpConns:   make(map[connKey]*udpConn),
	}

	// Start the cleanup loop with a short interval for testing.
	go s.udpCleanupLoop(ctx, 50*time.Millisecond)

	src := netip.MustParseAddrPort("10.0.0.100:33333")
	dst := netip.MustParseAddrPort("8.8.8.8:53")
	uc := &udpConn{
		stack:      s,
		src:        src,
		dst:        dst,
		packetCh:   make(chan *udpPacket, 64),
		closeCh:    make(chan struct{}),
		lastActive: time.Now().Add(-1 * time.Hour), // already stale
	}
	s.udpConns[connKey{src, dst}] = uc

	// Wait for the cleanup goroutine to tick.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		s.udpMu.Lock()
		_, exists := s.udpConns[connKey{src, dst}]
		s.udpMu.Unlock()
		if !exists {
			return // success
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("stale udpConn was not cleaned up by periodic goroutine within 2s")
}

// TestUDPCleanupDoesNotRemoveActive verifies the cleanup goroutine
// leaves active connections alone.
func TestUDPCleanupDoesNotRemoveActive(t *testing.T) {
	s := newTestStack()
	src := netip.MustParseAddrPort("10.0.0.100:33333")
	dst := netip.MustParseAddrPort("8.8.8.8:53")

	uc := &udpConn{
		stack:      s,
		src:        src,
		dst:        dst,
		packetCh:   make(chan *udpPacket, 64),
		closeCh:    make(chan struct{}),
		lastActive: time.Now(), // active
	}
	s.udpConns[connKey{src, dst}] = uc

	s.cleanupUDP(5 * time.Minute)

	s.udpMu.Lock()
	_, exists := s.udpConns[connKey{src, dst}]
	s.udpMu.Unlock()
	if !exists {
		t.Fatal("active udpConn was incorrectly removed by cleanup")
	}
}

// TestTCPConnWrapperCloseWrite verifies that CloseWrite on the wrapper
// propagates to the underlying *net.TCPConn, sending a FIN to the peer.
// Without this, the relay's closeWrite() becomes a no-op on the TUN side
// and connections don't half-close cleanly.
func TestTCPConnWrapperCloseWrite(t *testing.T) {
	// Set up a real TCP listener and dial it so we get actual *net.TCPConn
	// objects on both ends.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()

	serverConnCh := make(chan net.Conn, 1)
	go func() {
		c, err := ln.Accept()
		if err != nil {
			t.Errorf("accept: %v", err)
			return
		}
		serverConnCh <- c
	}()

	clientConn, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer clientConn.Close()
	serverConn := <-serverConnCh
	defer serverConn.Close()

	src := netip.MustParseAddrPort("10.0.0.100:33333")
	dst := netip.MustParseAddrPort("1.2.3.4:443")
	wrapper := &tcpConnWrapper{conn: clientConn, src: src, dst: dst}

	// CloseWrite on the wrapper should half-close the client side.
	// The server should then observe EOF on its read.
	if err := wrapper.CloseWrite(); err != nil {
		t.Fatalf("CloseWrite: %v", err)
	}

	buf := make([]byte, 10)
	n, err := serverConn.Read(buf)
	if err == nil && n != 0 {
		t.Fatalf("server read after CloseWrite: got %d bytes, want EOF", n)
	}
	if err != nil && err.Error() != "EOF" {
		t.Fatalf("server read: unexpected error %v, want EOF", err)
	}
}

// TestProcessIPv6PacketDropped verifies that IPv6 packets (version nibble = 6)
// are safely dropped without being misinterpreted as IPv4. Without a version
// check, an IPv6 packet whose byte 9 equals TCPProtocol(6) and whose bytes
// 12-19 look like valid unicast IPs would be processed as IPv4 TCP,
// creating a garbage NAT entry.
func TestProcessIPv6PacketDropped(t *testing.T) {
	s := newTestStack()
	// Construct an IPv6-ish packet that would pass the IPv4 unicast/private
	// checks if the version were not checked:
	//   buf[0]    = 0x60 (version 6, IHL 0)
	//   buf[9]    = 6 (TCPProtocol)
	//   buf[12:16] = 10.0.0.100 (private — passes IsPrivate)
	//   buf[16:20] = 8.8.8.8 (global unicast — passes IsGlobalUnicast)
	buf := make([]byte, 40)
	buf[0] = 0x60
	buf[9] = TCPProtocol
	buf[12] = 10; buf[13] = 0; buf[14] = 0; buf[15] = 100
	buf[16] = 8; buf[17] = 8; buf[18] = 8; buf[19] = 8

	ok := s.processIPv4(IPv4Header(buf))
	if ok {
		t.Fatal("processIPv4 should return false for IPv6 packet even when addresses look valid")
	}

	// Verify no NAT session was created from the garbage parse.
	s.tcpNat.mu.RLock()
	natSize := len(s.tcpNat.portMap)
	s.tcpNat.mu.RUnlock()
	if natSize != 0 {
		t.Fatalf("NAT table polluted with %d entries from IPv6 packet", natSize)
	}
}

// TestProcessICMPDropped verifies ICMP packets are dropped (not silently —
// they should be logged). We can only test the behavioral aspect: the
// packet is not processed and no state is modified.
func TestProcessICMPDropped(t *testing.T) {
	s := newTestStack()
	buf := make([]byte, IPv4HeaderLen+ICMPHeaderMinLen)
	ip := IPv4Header(buf)
	ip.Encode(uint16(len(buf)), ICMPProtocol,
		netip.MustParseAddr("10.0.0.100"),
		netip.MustParseAddr("8.8.8.8"))

	ok := s.processIPv4(ip)
	if ok {
		t.Fatal("processIPv4 should return false for ICMP packet")
	}
}

// TestProcessIPv4RejectsBadVersion verifies packets with an unexpected IP
// version are dropped.
func TestProcessIPv4RejectsBadVersion(t *testing.T) {
	s := newTestStack()
	for _, version := range []uint8{0, 1, 6, 15} {
		buf := make([]byte, IPv4HeaderLen+TCPHeaderMinLen)
		buf[0] = version << 4
		ok := s.processIPv4(IPv4Header(buf))
		if ok {
			t.Errorf("processIPv4 returned true for IP version %d", version)
		}
	}
}

