package stack

// OnesComplementSum returns the 16-bit one's complement of the one's
// complement sum of data, or 0xFFFF if the sum is 0.
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
	res := uint16(sum)
	if res == 0 && len(b) > 0 {
		return 0xFFFF
	}
	return res
}
