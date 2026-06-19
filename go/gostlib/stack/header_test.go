package stack

import (
	"net/netip"
	"testing"
)

func TestIPv4HeaderEncode(t *testing.T) {
	src := netip.MustParseAddr("10.0.0.2")
	dst := netip.MustParseAddr("10.0.0.3")
	totalLen := uint16(40) // 20 IP + 20 TCP

	buf := make([]byte, totalLen)
	ip := IPv4Header(buf)
	ip.Encode(totalLen, TCPProtocol, src, dst)

	if ip.Version() != 4 {
		t.Fatalf("version = %d, want 4", ip.Version())
	}
	if ip.HeaderLen() != 20 {
		t.Fatalf("headerLen = %d, want 20", ip.HeaderLen())
	}
	if ip.TotalLen() != totalLen {
		t.Fatalf("totalLen = %d, want %d", ip.TotalLen(), totalLen)
	}
	if ip.Protocol() != TCPProtocol {
		t.Fatalf("protocol = %d, want %d", ip.Protocol(), TCPProtocol)
	}
	if ip.SrcAddr() != src {
		t.Fatalf("srcAddr = %s, want %s", ip.SrcAddr(), src)
	}
	if ip.DstAddr() != dst {
		t.Fatalf("dstAddr = %s, want %s", ip.DstAddr(), dst)
	}
	// TTL
	if buf[8] != 64 {
		t.Fatalf("TTL = %d, want 64", buf[8])
	}
}

func TestIPv4ChecksumCorrectness(t *testing.T) {
	// After setting the correct checksum, OnesComplementSum over the
	// entire header must equal 0xFFFF.
	src := netip.MustParseAddr("192.168.1.1")
	dst := netip.MustParseAddr("10.0.0.1")
	buf := make([]byte, IPv4HeaderLen)
	ip := IPv4Header(buf)
	ip.Encode(IPv4HeaderLen, TCPProtocol, src, dst)

	cs := ip.CalculateChecksum()
	ip.SetChecksum(cs)

	sum := OnesComplementSum(buf[:IPv4HeaderLen])
	if sum != 0xFFFF {
		t.Fatalf("OnesComplementSum after checksum = 0x%04X, want 0xFFFF", sum)
	}
}

func TestIPv4ChecksumKnownVector(t *testing.T) {
	// Known vector: IPv4 header from RFC 1071 example.
	// 45 00 00 73 00 00 40 00 40 11 [checksum] c0 a8 00 01 c0 a8 00 c7
	buf := []byte{
		0x45, 0x00, 0x00, 0x73, 0x00, 0x00, 0x40, 0x00,
		0x40, 0x11, 0x00, 0x00, 0xc0, 0xa8, 0x00, 0x01,
		0xc0, 0xa8, 0x00, 0xc7,
	}
	ip := IPv4Header(buf)

	got := ip.CalculateChecksum()
	// RFC 1071 expected: 0xb861
	want := uint16(0xb861)
	if got != want {
		t.Fatalf("CalculateChecksum = 0x%04X, want 0x%04X", got, want)
	}

	// Verify: after setting, sum must be 0xFFFF
	ip.SetChecksum(got)
	if s := OnesComplementSum(buf[:IPv4HeaderLen]); s != 0xFFFF {
		t.Fatalf("post-checksum sum = 0x%04X, want 0xFFFF", s)
	}
}

func TestTCPHeaderFields(t *testing.T) {
	buf := make([]byte, 40)
	_ = IPv4Header(buf[:20])
	tcp := TCPHeader(buf[20:])

	tcp.SetSrcPort(12345)
	tcp.SetDstPort(443)
	tcp.SetSeqNum(0x12345678)
	tcp.SetAckNum(0x9abcdef0)
	tcp.SetFlags(TCPFlagSYN)
	// Set data offset to 5 (20-byte header) — normally set by the kernel.
	buf[20+12] = 0x50

	if tcp.SrcPort() != 12345 {
		t.Fatalf("srcPort = %d, want 12345", tcp.SrcPort())
	}
	if tcp.DstPort() != 443 {
		t.Fatalf("dstPort = %d, want 443", tcp.DstPort())
	}
	if tcp.SeqNum() != 0x12345678 {
		t.Fatalf("seq = 0x%x, want 0x12345678", tcp.SeqNum())
	}
	if tcp.AckNum() != 0x9abcdef0 {
		t.Fatalf("ack = 0x%x, want 0x9abcdef0", tcp.AckNum())
	}
	if tcp.Flags()&TCPFlagSYN == 0 {
		t.Fatal("SYN flag not set")
	}
	if tcp.DataOff() < TCPHeaderMinLen {
		t.Fatalf("dataOff = %d, want >= %d", tcp.DataOff(), TCPHeaderMinLen)
	}
}

func TestTCPChecksumCorrectness(t *testing.T) {
	// Build a valid IP+TCP packet and verify the TCP checksum.
	src := netip.MustParseAddr("10.0.0.2")
	dst := netip.MustParseAddr("1.2.3.4")
	totalLen := uint16(IPv4HeaderLen + TCPHeaderMinLen)
	buf := make([]byte, totalLen)

	ip := IPv4Header(buf)
	ip.Encode(totalLen, TCPProtocol, src, dst)

	tcp := TCPHeader(ip.Payload())
	tcp.SetSrcPort(12345)
	tcp.SetDstPort(443)
	// Set data offset to 5 (20 bytes)
	buf[IPv4HeaderLen+12] = 0x50

	cs := tcp.CalculateChecksum(ip)
	tcp.SetChecksum(cs)

	// Verify: tcpUDPChecksum over the pseudo-header + payload should
	// equal 0xFFFF after the complement is set.
	sum := tcpUDPChecksum(ip, TCPProtocol)
	if sum != 0xFFFF {
		t.Fatalf("tcpUDPChecksum after checksum set = 0x%04X, want 0xFFFF", sum)
	}
}

func TestUDPHeaderFields(t *testing.T) {
	buf := make([]byte, 8)
	udp := UDPHeader(buf)

	udp.SetSrcPort(53)
	udp.SetDstPort(12345)
	udp.SetLength(100)

	if udp.SrcPort() != 53 {
		t.Fatalf("srcPort = %d, want 53", udp.SrcPort())
	}
	if udp.DstPort() != 12345 {
		t.Fatalf("dstPort = %d, want 12345", udp.DstPort())
	}
	if udp.Length() != 100 {
		t.Fatalf("length = %d, want 100", udp.Length())
	}
}

func TestUDPChecksumCorrectness(t *testing.T) {
	src := netip.MustParseAddr("10.0.0.2")
	dst := netip.MustParseAddr("8.8.8.8")
	payload := []byte("hello")
	totalLen := uint16(IPv4HeaderLen + UDPHeaderLen + len(payload))
	buf := make([]byte, totalLen)

	ip := IPv4Header(buf)
	ip.Encode(totalLen, UDPProtocol, src, dst)

	udp := UDPHeader(ip.Payload())
	udp.SetSrcPort(12345)
	udp.SetDstPort(53)
	udp.SetLength(uint16(UDPHeaderLen + len(payload)))
	copy(udp.Payload(), payload)

	// Correct checksum = ^tcpUDPChecksum
	cs := uint16(^tcpUDPChecksum(ip, UDPProtocol))
	udp.SetChecksum(cs)

	// After setting the complement, sum should be 0xFFFF
	sum := tcpUDPChecksum(ip, UDPProtocol)
	if sum != 0xFFFF {
		t.Fatalf("tcpUDPChecksum after checksum set = 0x%04X, want 0xFFFF", sum)
	}
}

func TestOnesComplementSumZero(t *testing.T) {
	// All zeros: result should be 0xFFFF (RFC 1071: +0 is represented as 0xFFFF)
	buf := []byte{0, 0, 0, 0}
	got := OnesComplementSum(buf)
	if got != 0xFFFF {
		t.Fatalf("OnesComplementSum of all zeros = 0x%04X, want 0xFFFF", got)
	}

	// Empty input: result should be 0
	got = OnesComplementSum([]byte{})
	if got != 0 {
		t.Fatalf("OnesComplementSum of empty = 0x%04X, want 0", got)
	}
}

func TestOnesComplementSumOddLength(t *testing.T) {
	// Single byte 0xAB: padded with zero → 0xAB00, sum = 0xAB00
	buf := []byte{0xAB}
	got := OnesComplementSum(buf)
	want := uint16(0xAB00)
	if got != want {
		t.Fatalf("OnesComplementSum odd length = 0x%04X, want 0x%04X", got, want)
	}
}

func TestAddrFrom4PutAddr4(t *testing.T) {
	addr := netip.MustParseAddr("192.168.1.1")
	buf := make([]byte, 4)
	putAddr4(buf, addr)

	got := addrFrom4(buf)
	if got != addr {
		t.Fatalf("addrFrom4/putAddr4 roundtrip: got %s, want %s", got, addr)
	}
}
