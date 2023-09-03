package main

import (
	"io"
	"net"
	"strings"

	"github.com/cretz/bine/control"
	"github.com/cretz/bine/process"
	tor047 "github.com/cretz/bine/process/embedded/tor-0.4.7"
	"github.com/cretz/bine/tor"
	"github.com/ginuerzh/gost"
)

type torServer struct {
	node      gost.Node
	logWriter io.Writer
	tor       *tor.Tor
}

func (s *torServer) Addr() net.Addr {
	tcpAddr, _ := net.ResolveTCPAddr("tcp", s.node.Addr)
	return tcpAddr
}

func (s *torServer) Init(opts ...gost.ServerOption) {}

func (s *torServer) Serve(h gost.Handler, opts ...gost.ServerOption) error {
	t, err := tor.Start(nil, &tor.StartConf{
		ProcessCreator:    NewCreator(),
		EnableNetwork:     false,
		NoAutoSocksPort:   true,
		RetainTempDataDir: false,
		DebugWriter:       s.logWriter,
	})
	if err != nil {
		return err
	}

	// Remove : from left of ':9050'
	if err := t.Control.SetConf(control.KeyVals("SocksPort", strings.TrimPrefix(s.node.Addr, ":"))...); err != nil {
		return err
	}

	for k, v := range s.node.Values {
		if err := t.Control.SetConf(control.KeyVals(k, v[0])...); err != nil {
			return err
		}
	}

	s.tor = t

	return t.EnableNetwork(nil, false)
}

func (s *torServer) Close() error {
	return s.tor.Close()
}

// NewCreator creates a process.Creator for statically-linked Tor embedded in
// the binary.
func NewCreator() process.Creator {
	return tor047.NewCreator()
}
