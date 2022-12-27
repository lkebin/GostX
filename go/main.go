package main

/*
#include <stdio.h>
struct info {
    char* listen;
};
*/
import "C"

import (
	"context"
	"crypto/tls"
	"errors"
	"flag"
	"net/http"
	"os"
	"runtime"
	"strings"
	"unsafe"

	_ "net/http/pprof"

	"github.com/ginuerzh/gost"
)

var (
	configureFile string
	baseCfg       = &baseConfig{}
	logger        *Logger
	pprofAddr     string
	pprofEnabled  = os.Getenv("PROFILING") != ""
	pprofServer   *http.Server
)

//export gostInfo
func gostInfo() *C.struct_info {
	var i string
	s := (*C.struct_info)(C.malloc(C.size_t(unsafe.Sizeof(C.struct_info{}))))
	for k, v := range routers {
		if k > 0 {
			i += ";"
		}
		i += v.node.String()
	}
	s.listen = C.CString(i)
	return s
}

//export gostStop
func gostStop() int {
	clean()
	return 0
}

//export gostRun
func gostRun(args *C.char, fd *C.long) int {
	var w *os.File
	if logger == nil {
		w = os.NewFile(uintptr(int64(*fd)), "")
		// Using swift process pipe file descriptor for logger output
		logger = NewLogger(w)
	}

	gost.SetLogger(logger)

	var printVersion bool

	fs := flag.NewFlagSet("GostArguments", flag.ExitOnError)
	fs.Var(&baseCfg.route.ChainNodes, "F", "forward address, can make a forward chain")
	fs.Var(&baseCfg.route.ServeNodes, "L", "listen address, can listen on multiple ports (required)")
	fs.IntVar(&baseCfg.route.Mark, "M", 0, "Specify out connection mark")
	fs.StringVar(&configureFile, "C", "", "configure file")
	fs.StringVar(&baseCfg.route.Interface, "I", "", "Interface to bind")
	fs.BoolVar(&baseCfg.Debug, "D", false, "enable debug log")
	fs.BoolVar(&printVersion, "V", false, "print version")
	if pprofEnabled {
		fs.StringVar(&pprofAddr, "P", ":6060", "profiling HTTP server address")
	}

	fs.Parse(strings.Split(C.GoString(args), " "))
	fs.SetOutput(w)

	if printVersion {
		logger.Logf("gost %s (%s %s/%s)\n", gost.Version, runtime.Version(), runtime.GOOS, runtime.GOARCH)
		return 0
	}

	if configureFile != "" {
		_, err := parseBaseConfig(configureFile)
		if err != nil {
			logger.Log(err)
			goto Fail
		}
	}

	if fs.NFlag() == 0 {
		fs.PrintDefaults()
		return 0
	}

	if baseCfg.Debug {
		logger.Logf("Run gost with arguments: %s\n", C.GoString(args))
	}

	if err := serve(); err != nil {
		logger.Log(err)
		goto Fail
	}

	return 0

Fail:
	clean()
	return 1
}

func clean() {
	if pprofEnabled {
		if pprofServer != nil {
			pprofServer.Shutdown(context.Background())
		}
	}

	for i := range routers {
		logger.Log("stopping ", routers[i].node.Addr)

		err := routers[i].Close()
		if err != nil {
			logger.Log(err)
		}
	}

	// clean resources
	routers = nil
	baseCfg = &baseConfig{}
}

func serve() error {
	if pprofEnabled {
		pprofServer = &http.Server{
			Addr: pprofAddr,
		}
		logger.Log("profiling server on", pprofAddr)

		go func() {
			logger.Log("profiling server on", pprofAddr)

			if err := pprofServer.ListenAndServe(); err != nil {
				logger.Log(err)
			}
		}()
	}

	// NOTE: as of 2.6, you can use custom cert/key files to initialize the default certificate.
	tlsConfig, err := tlsConfig(defaultCertFile, defaultKeyFile, "")
	if err != nil {
		// generate random self-signed certificate.
		cert, err := gost.GenCertificate()
		if err != nil {
			return err
		}
		tlsConfig = &tls.Config{
			Certificates: []tls.Certificate{cert},
		}
	} else {
		logger.Log("load TLS certificate files OK")
	}

	gost.DefaultTLSConfig = tlsConfig

	return start()
}

func start() error {
	gost.Debug = baseCfg.Debug

	rts, err := baseCfg.route.GenRouters()
	if err != nil {
		return err
	}

	routers = append(routers, rts...)

	for _, route := range baseCfg.Routes {
		rts, err := route.GenRouters()
		if err != nil {
			return err
		}
		routers = append(routers, rts...)
	}

	if len(routers) == 0 {
		return errors.New("invalid config")
	}

	for i := range routers {
		go routers[i].Serve()
	}

	return nil
}

func main() {}
