package stack

import (
	"context"
	"net/netip"
)

// Handler receives TCP/UDP sessions from the system stack.
type Handler interface {
	// PrepareConnection is called before a new session is created.
	// Return an error to drop the session.
	PrepareConnection(network string, source, destination netip.AddrPort) error
	// NewConnection is called for each new TCP connection.
	NewConnection(ctx context.Context, conn TCPConn, source, destination netip.AddrPort)
	// NewPacketConnection is called for each new UDP session.
	NewPacketConnection(ctx context.Context, conn PacketConn, source, destination netip.AddrPort)
}

// PacketConn represents a UDP packet connection from the TUN.
// The destination is implicit (the original source that sent to the TUN),
// so WritePacket only takes the payload.
type PacketConn interface {
	ReadPacket() ([]byte, netip.AddrPort, error)
	WritePacket(data []byte) error
	Close() error
}

// TCPConn is a wrapper around a TCP connection from the stack.
type TCPConn interface {
	Read(b []byte) (int, error)
	Write(b []byte) (int, error)
	Close() error
	CloseWrite() error
	LocalAddr() netip.AddrPort
	RemoteAddr() netip.AddrPort
}
