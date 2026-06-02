package gostlib

import (
	"context"
	"encoding/binary"
	"fmt"
	"net"
	"sync"
	"sync/atomic"

	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/checksum"
	"gvisor.dev/gvisor/pkg/tcpip/header"

	xchain "github.com/go-gost/x/chain"
)

type mixedEngine struct {
	tunFd    int
	mtu      int
	natTable *tunNATTable
	udpStack *gVisorUDPStack
	router   *xchain.Router
	dnsAddr  string

	tcpListener net.Listener
	listenerWg  sync.WaitGroup

	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup
}

func newMixedEngine(tunFd, mtu int, router *xchain.Router, dnsAddr string) (*mixedEngine, error) {
	natTable := newNATTable()
	udpStack, err := newUDPStack(uint32(mtu), router, dnsAddr)
	if err != nil {
		natTable.Close()
		return nil, fmt.Errorf("create UDP stack: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	return &mixedEngine{
		tunFd:    tunFd,
		mtu:      mtu,
		natTable: natTable,
		udpStack: udpStack,
		router:   router,
		dnsAddr:  dnsAddr,
		ctx:      ctx,
		cancel:   cancel,
	}, nil
}

func (e *mixedEngine) startTCPListener() error {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return fmt.Errorf("listen TCP: %w", err)
	}
	e.tcpListener = ln
	logVPN("[tcp] listener on %s", ln.Addr().String())

	e.listenerWg.Add(1)
	go func() {
		defer e.listenerWg.Done()
		for {
			conn, err := ln.Accept()
			if err != nil {
				select {
				case <-e.ctx.Done():
					return
				default:
					logVPN("[tcp] accept error: %v", err)
					return
				}
			}
			go e.handleTCPConn(conn)
		}
	}()
	return nil
}

func (e *mixedEngine) handleTCPConn(conn net.Conn) {
	defer conn.Close()

	localAddr := conn.LocalAddr().(*net.TCPAddr)
	localPort := uint16(localAddr.Port)

	_, dst, ok := e.natTable.ReverseLookup(localPort)
	if !ok {
		logVPN("[tcp] no NAT entry for port %d", localPort)
		return
	}
	defer e.natTable.Remove(localPort)

	var upstream net.Conn
	var err error
	if e.dnsAddr != "" && dst.IP.Equal(net.ParseIP(vpnDNSVirtualAddr)) && dst.Port == vpnDNSVirtualPort {
		upstream, err = net.Dial("tcp", e.dnsAddr)
	} else {
		upstream, err = e.router.Dial(e.ctx, "tcp", dst.String())
	}
	if err != nil {
		atomic.AddInt64(&vpnFailedConns, 1)
		logVPN("[tcp] dial %s failed: %v", dst.String(), err)
		return
	}
	defer upstream.Close()

	relay(conn, upstream)
}

func (e *mixedEngine) run() error {
	if err := e.startTCPListener(); err != nil {
		return err
	}

	e.wg.Add(1)
	go e.udpOutboundLoop()

	buf := make([]byte, 65535)
	for {
		select {
		case <-e.ctx.Done():
			return e.ctx.Err()
		default:
		}

		n, err := unix.Read(e.tunFd, buf)
		if err != nil {
			select {
			case <-e.ctx.Done():
				return e.ctx.Err()
			default:
				return fmt.Errorf("tun read: %w", err)
			}
		}
		if n < 20 {
			continue
		}

		e.dispatchPacket(buf[:n])
	}
}

func (e *mixedEngine) dispatchPacket(data []byte) {
	if data[0]>>4 != 4 {
		return // Only IPv4
	}
	ihl := int(data[0]&0x0f) * 4
	if ihl < 20 || len(data) < ihl {
		return
	}
	protocol := data[9]

	switch protocol {
	case 6: // TCP
		dstIP := net.IP(data[16:20])
		if dstIP.Equal(net.IPv4(10, 0, 0, 0)) || dstIP[0] == 127 {
			// dst is in VPN subnet or localhost -> likely a response from our NAT
			e.handleTCPResponse(data, ihl)
		} else {
			e.handleTCPOutbound(data, ihl)
		}
	case 17: // UDP
		e.udpStack.InjectInbound(data)
	case 1: // ICMP
		e.handleICMP(data, ihl)
	}
}

func (e *mixedEngine) handleTCPOutbound(data []byte, ihl int) {
	if len(data) < ihl+20 {
		return
	}

	srcIP := data[12:16]
	dstIP := data[16:20]
	srcPort := binary.BigEndian.Uint16(data[ihl : ihl+2])
	dstPort := binary.BigEndian.Uint16(data[ihl+2 : ihl+4])

	srcAddr := net.TCPAddr{IP: net.IP(srcIP), Port: int(srcPort)}
	dstAddr := net.TCPAddr{IP: net.IP(dstIP), Port: int(dstPort)}

	localPort := e.natTable.CreateOrLookup(srcAddr, dstAddr)

	// Rewrite dst to 127.0.0.1:localPort
	copy(data[16:20], []byte{127, 0, 0, 1})
	binary.BigEndian.PutUint16(data[ihl+2:ihl+4], localPort)

	// Recalculate IP checksum
	data[10] = 0
	data[11] = 0
	ipSum := ^checksum.Checksum(data[:ihl], 0)
	binary.BigEndian.PutUint16(data[10:12], ipSum)

	// Recalculate TCP checksum
	totalLen := int(binary.BigEndian.Uint16(data[2:4]))
	tcpLen := totalLen - ihl
	if tcpLen < 0 {
		return
	}
	tcpSeg := data[ihl : ihl+tcpLen]
	// Zero the TCP checksum field
	tcpSeg[16] = 0
	tcpSeg[17] = 0
	xsum := header.PseudoHeaderChecksum(
		header.TCPProtocolNumber,
		tcpip.AddrFrom4([4]byte{srcIP[0], srcIP[1], srcIP[2], srcIP[3]}),
		tcpip.AddrFrom4([4]byte{data[16], data[17], data[18], data[19]}),
		uint16(tcpLen))
	xsum = checksum.Checksum(tcpSeg, xsum)
	binary.BigEndian.PutUint16(tcpSeg[16:18], ^xsum)

	unix.Write(e.tunFd, data)
}

func (e *mixedEngine) handleTCPResponse(data []byte, ihl int) {
	if len(data) < ihl+20 {
		return
	}

	srcPort := binary.BigEndian.Uint16(data[ihl : ihl+2])

	_, origDst, ok := e.natTable.ReverseLookup(srcPort)
	if !ok {
		return
	}

	// Restore original source (the real server address)
	origDstIP := origDst.IP.To4()
	if origDstIP == nil {
		return
	}
	copy(data[12:16], origDstIP)
	binary.BigEndian.PutUint16(data[ihl:ihl+2], uint16(origDst.Port))

	// Recalculate IP checksum
	data[10] = 0
	data[11] = 0
	ipSum := ^checksum.Checksum(data[:ihl], 0)
	binary.BigEndian.PutUint16(data[10:12], ipSum)

	// Recalculate TCP checksum (src changed)
	totalLen := int(binary.BigEndian.Uint16(data[2:4]))
	tcpLen := totalLen - ihl
	if tcpLen < 0 {
		return
	}
	tcpSeg := data[ihl : ihl+tcpLen]
	tcpSeg[16] = 0
	tcpSeg[17] = 0
	xsum := header.PseudoHeaderChecksum(
		header.TCPProtocolNumber,
		tcpip.AddrFrom4([4]byte{origDstIP[0], origDstIP[1], origDstIP[2], origDstIP[3]}),
		tcpip.AddrFrom4([4]byte{data[16], data[17], data[18], data[19]}),
		uint16(tcpLen))
	xsum = checksum.Checksum(tcpSeg, xsum)
	binary.BigEndian.PutUint16(tcpSeg[16:18], ^xsum)

	unix.Write(e.tunFd, data)
}

func (e *mixedEngine) handleICMP(data []byte, ihl int) {
	if len(data) < ihl+8 {
		return
	}
	icmpType := data[ihl]
	if icmpType != 8 {
		return // Only handle Echo Request
	}

	atomic.AddInt64(&vpnICMPConns, 1)

	// Swap source and destination IP
	src := make([]byte, 4)
	dst := make([]byte, 4)
	copy(src, data[12:16])
	copy(dst, data[16:20])
	copy(data[12:16], dst)
	copy(data[16:20], src)

	// Change type from Echo Request (8) to Echo Reply (0)
	data[ihl] = 0

	// Recalculate IP checksum
	data[10] = 0
	data[11] = 0
	ipSum := ^checksum.Checksum(data[:ihl], 0)
	binary.BigEndian.PutUint16(data[10:12], ipSum)

	// Recalculate ICMP checksum
	icmpLen := len(data) - ihl
	icmpData := data[ihl : ihl+icmpLen]
	binary.BigEndian.PutUint16(icmpData[2:4], 0)
	binary.BigEndian.PutUint16(icmpData[2:4], ^checksum.Checksum(icmpData, 0))

	unix.Write(e.tunFd, data)
}

func (e *mixedEngine) udpOutboundLoop() {
	defer e.wg.Done()
	for {
		pkt, err := e.udpStack.ReadOutbound(e.ctx)
		if err != nil {
			return
		}
		if len(pkt) > 0 {
			unix.Write(e.tunFd, pkt)
		}
	}
}

func (e *mixedEngine) Close() {
	e.cancel()

	if e.tcpListener != nil {
		e.tcpListener.Close()
	}
	e.udpStack.Close()
	e.natTable.Close()

	e.listenerWg.Wait()
	e.wg.Wait()
}
