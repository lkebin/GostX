package gostlib

import (
	"bytes"
	"context"
	"fmt"
	"net"
	"sync/atomic"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"

	xchain "github.com/go-gost/x/chain"
)

// gVisorUDPStack is a minimal UDP-only gVisor network stack that reads/writes
// raw IPv4 packets from/to a TUN fd. It is used by the mixed-mode TUN stack
// (Task 2) when UDP traffic must bypass tun2socks and be handled in-process.
type gVisorUDPStack struct {
	stack          *stack.Stack
	endpoint       *channel.Endpoint
	router         *xchain.Router
	dnsServiceAddr string
	ctx            context.Context
	cancel         context.CancelFunc
}

func newUDPStack(mtu uint32, router *xchain.Router, dnsServiceAddr string) (*gVisorUDPStack, error) {
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{udp.NewProtocol},
	})

	ep := channel.New(1024, mtu, tcpip.LinkAddress(""))
	if err := s.CreateNIC(1, ep); err != nil {
		return nil, fmt.Errorf("create NIC: %w", err)
	}

	if err := s.AddProtocolAddress(1, tcpip.ProtocolAddress{
		Protocol: ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddressWithPrefix{
			Address:   tcpip.AddrFrom4([4]byte{10, 0, 0, 2}),
			PrefixLen: 24,
		},
	}, stack.AddressProperties{}); err != nil {
		return nil, fmt.Errorf("add protocol address: %w", err)
	}

	s.SetRouteTable([]tcpip.Route{{
		Destination: header.IPv4EmptySubnet,
		NIC:         1,
	}})

	ctx, cancel := context.WithCancel(context.Background())
	us := &gVisorUDPStack{
		stack:          s,
		endpoint:       ep,
		router:         router,
		dnsServiceAddr: dnsServiceAddr,
		ctx:            ctx,
		cancel:         cancel,
	}

	fwd := udp.NewForwarder(s, us.handleUDP)
	s.SetTransportProtocolHandler(udp.ProtocolNumber, fwd.HandlePacket)

	return us, nil
}

func (us *gVisorUDPStack) handleUDP(req *udp.ForwarderRequest) {
	go func() {
		id := req.ID()
		dst := net.JoinHostPort(id.LocalAddress.String(), fmt.Sprintf("%d", id.LocalPort))
		n := atomic.AddInt64(&vpnUDPConns, 1)
		logVPN("[udp#%d] dial %s", n, dst)

		var wq waiter.Queue
		ep, err := req.CreateEndpoint(&wq)
		if err != nil {
			atomic.AddInt64(&vpnFailedConns, 1)
			logVPN("[udp#%d] create endpoint failed: %v", n, err)
			return
		}

		conn := gonetUDPConn{ep: ep, wq: &wq}

		var upstream net.Conn
		var dialErr error
		if us.dnsServiceAddr != "" &&
			id.LocalAddress.String() == vpnDNSVirtualAddr &&
			id.LocalPort == uint16(vpnDNSVirtualPort) {
			upstream, dialErr = net.Dial("udp", us.dnsServiceAddr)
		} else {
			upstream, dialErr = us.router.Dial(context.Background(), "udp", dst)
		}
		if dialErr != nil {
			atomic.AddInt64(&vpnFailedConns, 1)
			logVPN("[udp#%d] dial failed: %v", n, dialErr)
			ep.Close()
			return
		}
		defer upstream.Close()
		defer ep.Close()

		logVPN("[udp#%d] relaying %s", n, dst)
		relay(conn, upstream)
		logVPN("[udp#%d] done %s", n, dst)
	}()
}

// gonetUDPConn wraps a gVisor UDP transport endpoint as a net.Conn.
type gonetUDPConn struct {
	ep tcpip.Endpoint
	wq *waiter.Queue
}

func (c gonetUDPConn) Read(b []byte) (int, error) {
	waitEntry, ch := waiter.NewChannelEntry(waiter.EventIn)
	c.wq.EventRegister(&waitEntry)
	defer c.wq.EventUnregister(&waitEntry)

	for {
		var buf bytes.Buffer
		_, resErr := c.ep.Read(&buf, tcpip.ReadOptions{})
		if resErr != nil {
			if _, ok := resErr.(*tcpip.ErrWouldBlock); ok {
				<-ch
				continue
			}
			return 0, fmt.Errorf("udp read: %v", resErr)
		}
		return copy(b, buf.Bytes()), nil
	}
}

func (c gonetUDPConn) Write(b []byte) (int, error) {
	for {
		r := bytes.NewReader(b)
		_, resErr := c.ep.Write(r, tcpip.WriteOptions{})
		if resErr == nil {
			return len(b), nil
		}
		if _, ok := resErr.(*tcpip.ErrWouldBlock); ok {
			waitEntry, ch := waiter.NewChannelEntry(waiter.EventOut)
			c.wq.EventRegister(&waitEntry)
			<-ch
			c.wq.EventUnregister(&waitEntry)
			continue
		}
		return 0, fmt.Errorf("udp write: %v", resErr)
	}
}

func (c gonetUDPConn) Close() error {
	c.ep.Close()
	return nil
}

func (c gonetUDPConn) LocalAddr() net.Addr             { return nil }
func (c gonetUDPConn) RemoteAddr() net.Addr            { return nil }
func (c gonetUDPConn) SetDeadline(t time.Time) error      { return nil }
func (c gonetUDPConn) SetReadDeadline(t time.Time) error  { return nil }
func (c gonetUDPConn) SetWriteDeadline(t time.Time) error { return nil }

// InjectInbound injects a raw IPv4 packet from the TUN fd into gVisor.
func (us *gVisorUDPStack) InjectInbound(rawIP []byte) {
	pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
		Payload: buffer.MakeWithView(buffer.NewViewWithData(rawIP)),
	})
	us.endpoint.InjectInbound(header.IPv4ProtocolNumber, pkt)
}

// ReadOutbound blocks until gVisor produces an outbound packet, then returns
// the raw IP bytes to write to the TUN fd.
func (us *gVisorUDPStack) ReadOutbound(ctx context.Context) ([]byte, error) {
	pkt := us.endpoint.ReadContext(ctx)
	if pkt == nil {
		return nil, ctx.Err()
	}
	// Combine slices into one buffer.
	slices := pkt.AsSlices()
	var total int
	for _, s := range slices {
		total += len(s)
	}
	buf := make([]byte, total)
	n := 0
	for _, s := range slices {
		n += copy(buf[n:], s)
	}
	return buf, nil
}

func (us *gVisorUDPStack) Close() {
	us.cancel()
	us.stack.Close()
}
