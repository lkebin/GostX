package stack

// OnesComplementSum returns the 16-bit one's complement sum of b.
// The result is suitable for use as an IP/TCP/UDP checksum field after
// bitwise negation by the caller (e.g. `^OnesComplementSum(...)`).
// When b is non-empty and the sum is zero, the pre-negation result is
// zero; after negation the caller stores 0xFFFF, which is the correct
// "valid checksum" value per RFC 791/793.
func OnesComplementSum(b []byte) uint16 {
	var sum uint32
	for i := 0; i < len(b)-1; i += 2 {
		sum += uint32(b[i])<<8 | uint32(b[i+1])
	}
	if len(b)%2 != 0 {
		sum += uint32(b[len(b)-1]) << 8
	}
	for sum > 0xFFFF {
		sum = (sum & 0xFFFF) + (sum >> 16)
	}
	return uint16(sum)
}
