//go:build !darwin

package libgost

// tunStackType selects the sing-tun stack implementation.
// The "system" stack is lightweight (kernel TCP + IP header rewriting)
// and works on Android, Linux, and Windows.
const tunStackType = "system"
