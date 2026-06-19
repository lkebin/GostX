package stack

import (
	"encoding/binary"
	"net/netip"
)

// Protocol numbers
const (
	TCPProtocol  = 6
	UDPProtocol  = 17
	ICMPProtocol = 1
)

const (
	IPv4HeaderLen    = 20
	TCPHeaderMinLen  = 20
	UDPHeaderLen     = 8
	ICMPHeaderMinLen = 8
)

const (
	TCPFlagFIN = 1 << 0
	TCPFlagSYN = 1 << 1
	TCPFlagRST = 1 << 2
	TCPFlagPSH = 1 << 3
	TCPFlagACK = 1 << 4
)

// IPv4Header provides typed access to a raw IPv4 header byte slice.
type IPv4Header []byte

func (h IPv4Header) Version() uint8      { return h[0] >> 4 }
func (h IPv4Header) HeaderLen() int      { return int(h[0]&0x0F) * 4 }
func (h IPv4Header) TotalLen() uint16    { return binary.BigEndian.Uint16(h[2:4]) }
func (h IPv4Header) Protocol() uint8     { return h[9] }
func (h IPv4Header) Checksum() uint16    { return binary.BigEndian.Uint16(h[10:12]) }
func (h IPv4Header) SrcAddr() netip.Addr { return addrFrom4(h[12:16]) }
func (h IPv4Header) DstAddr() netip.Addr { return addrFrom4(h[16:20]) }
func (h IPv4Header) Payload() []byte     { return h[h.HeaderLen():] }

func (h IPv4Header) SetTotalLen(v uint16)    { binary.BigEndian.PutUint16(h[2:4], v) }
func (h IPv4Header) SetChecksum(v uint16)    { binary.BigEndian.PutUint16(h[10:12], v) }
func (h IPv4Header) SetSrcAddr(a netip.Addr) { putAddr4(h[12:16], a) }
func (h IPv4Header) SetDstAddr(a netip.Addr) { putAddr4(h[16:20], a) }

func (h IPv4Header) CalculateChecksum() uint16 {
	h.SetChecksum(0)
	return ^OnesComplementSum(h[:h.HeaderLen()])
}

// Encode fills an IPv4 header with the given fields.
func (h IPv4Header) Encode(totalLen uint16, protocol uint8, src, dst netip.Addr) {
	h[0] = 0x45 // version 4, IHL 5
	h[1] = 0    // DSCN/ECN
	binary.BigEndian.PutUint16(h[2:4], totalLen)
	binary.BigEndian.PutUint16(h[4:6], 0) // ID
	binary.BigEndian.PutUint16(h[6:8], 0) // flags/fragment
	h[8] = 64                              // TTL
	h[9] = protocol
	binary.BigEndian.PutUint16(h[10:12], 0) // checksum (to be computed)
	putAddr4(h[12:16], src)
	putAddr4(h[16:20], dst)
}

// TCPHeader provides typed access to a raw TCP header byte slice.
type TCPHeader []byte

func (h TCPHeader) SrcPort() uint16  { return binary.BigEndian.Uint16(h[0:2]) }
func (h TCPHeader) DstPort() uint16  { return binary.BigEndian.Uint16(h[2:4]) }
func (h TCPHeader) SeqNum() uint32   { return binary.BigEndian.Uint32(h[4:8]) }
func (h TCPHeader) AckNum() uint32   { return binary.BigEndian.Uint32(h[8:12]) }
func (h TCPHeader) DataOff() int     { return int(h[12]>>4) * 4 }
func (h TCPHeader) Flags() uint8     { return h[13] }
func (h TCPHeader) Checksum() uint16 { return binary.BigEndian.Uint16(h[16:18]) }
func (h TCPHeader) Payload() []byte  { return h[h.DataOff():] }

func (h TCPHeader) SetSrcPort(v uint16)  { binary.BigEndian.PutUint16(h[0:2], v) }
func (h TCPHeader) SetDstPort(v uint16)  { binary.BigEndian.PutUint16(h[2:4], v) }
func (h TCPHeader) SetSeqNum(v uint32)   { binary.BigEndian.PutUint32(h[4:8], v) }
func (h TCPHeader) SetAckNum(v uint32)   { binary.BigEndian.PutUint32(h[8:12], v) }
func (h TCPHeader) SetFlags(v uint8)     { h[13] = v }
func (h TCPHeader) SetChecksum(v uint16) { binary.BigEndian.PutUint16(h[16:18], v) }

func (h TCPHeader) CalculateChecksum(ipHdr IPv4Header) uint16 {
	h.SetChecksum(0)
	return ^tcpUDPChecksum(ipHdr, TCPProtocol)
}

func tcpUDPChecksum(ipHdr IPv4Header, protocol uint8) uint16 {
	payload := ipHdr.Payload()
	payloadLen := len(payload)
	src := ipHdr.SrcAddr().As4()
	dst := ipHdr.DstAddr().As4()

	// Pseudo-header + payload
	var sum uint32
	sum += uint32(src[0])<<8 | uint32(src[1])
	sum += uint32(src[2])<<8 | uint32(src[3])
	sum += uint32(dst[0])<<8 | uint32(dst[1])
	sum += uint32(dst[2])<<8 | uint32(dst[3])
	sum += uint32(protocol)
	sum += uint32(payloadLen)

	for i := 0; i < payloadLen-1; i += 2 {
		sum += uint32(payload[i])<<8 | uint32(payload[i+1])
	}
	if payloadLen%2 != 0 {
		sum += uint32(payload[payloadLen-1]) << 8
	}
	for sum > 0xFFFF {
		sum = (sum & 0xFFFF) + (sum >> 16)
	}
	// Return the pre-negation sum. Callers negate with ^ to get the stored
	// checksum. When sum==0, ^ produces 0xFFFF (correct per RFC 793/768).
	return uint16(sum)
}

// UDPHeader provides typed access to a raw UDP header byte slice.
type UDPHeader []byte

func (h UDPHeader) SrcPort() uint16  { return binary.BigEndian.Uint16(h[0:2]) }
func (h UDPHeader) DstPort() uint16  { return binary.BigEndian.Uint16(h[2:4]) }
func (h UDPHeader) Length() uint16   { return binary.BigEndian.Uint16(h[4:6]) }
func (h UDPHeader) Checksum() uint16 { return binary.BigEndian.Uint16(h[6:8]) }
// Payload returns the UDP data field, bounded by the UDP length field.
// Using the length field rather than the slice tail avoids delivering IP
// trailing-pad bytes to callers when the link layer forced packet alignment.
func (h UDPHeader) Payload() []byte {
	l := int(h.Length())
	if l < UDPHeaderLen || l > len(h) {
		return h[UDPHeaderLen:] // malformed header: best-effort fallback
	}
	return h[UDPHeaderLen:l]
}

func (h UDPHeader) SetSrcPort(v uint16)  { binary.BigEndian.PutUint16(h[0:2], v) }
func (h UDPHeader) SetDstPort(v uint16)  { binary.BigEndian.PutUint16(h[2:4], v) }
func (h UDPHeader) SetLength(v uint16)   { binary.BigEndian.PutUint16(h[4:6], v) }
func (h UDPHeader) SetChecksum(v uint16) { binary.BigEndian.PutUint16(h[6:8], v) }

func addrFrom4(b []byte) netip.Addr      { return netip.AddrFrom4([4]byte(b)) }
func putAddr4(b []byte, a netip.Addr) { copy(b, a.AsSlice()) }
