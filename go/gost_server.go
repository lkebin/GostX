package main

import (
	"net"

	"github.com/ginuerzh/gost"
)

type GostServer interface {
	Init(opts ...gost.ServerOption)
	Addr() net.Addr
	Serve(h gost.Handler, opts ...gost.ServerOption) error
	Close() error
}
