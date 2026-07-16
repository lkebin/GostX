//go:build darwin

package libgost

// On macOS with NEPacketTunnelProvider, the "system" stack (kernel TCP
// with IP header rewriting) does not reliably deliver rewritten packets
// back to the kernel TCP stack on the point-to-point utun interface.
// sing-box also defaults to gvisor on macOS when includeAllNetworks is
// enabled. Use gvisor (userspace TCP via gVisor netstack) instead.
const tunStackType = "gvisor"
