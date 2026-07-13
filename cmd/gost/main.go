// gostcli is a standalone debugging CLI (not part of the Android build).
// It starts gost services from a YAML config on the host machine (macOS),
// using the exact same go-gost-x version pinned in libgost/go.mod, so we
// can test whether the hysteria connector/dialer can actually reach the
// upstream proxy server, independent of the Android/gomobile build.
package main

import (
	"context"
	"fmt"
	"os"

	"github.com/go-gost/core/service"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/config/loader"
	serviceparser "github.com/go-gost/x/config/parsing/service"
	"github.com/sirupsen/logrus"
	"gopkg.in/yaml.v3"

	_ "github.com/go-gost/x/connector/direct"
	_ "github.com/go-gost/x/connector/hysteria"
	_ "github.com/go-gost/x/connector/socks/v5"
	_ "github.com/go-gost/x/dialer/hysteria"
	_ "github.com/go-gost/x/dialer/tcp"
	_ "github.com/go-gost/x/dialer/ws"
	_ "github.com/go-gost/x/handler/socks/v5"
	_ "github.com/go-gost/x/listener/tcp"
)

func main() {
	logrus.SetLevel(logrus.DebugLevel)

	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: gostcli <config.yaml>")
		os.Exit(1)
	}

	data, err := os.ReadFile(os.Args[1])
	if err != nil {
		fmt.Fprintf(os.Stderr, "read config: %v\n", err)
		os.Exit(1)
	}

	cfg := &config.Config{}
	if err := yaml.Unmarshal(data, cfg); err != nil {
		fmt.Fprintf(os.Stderr, "parse config: %v\n", err)
		os.Exit(1)
	}

	// loader.Load() also parses+registers cfg.Services internally (see
	// config/loader/loader.go); null them out first so we don't double-bind
	// the same listener address (mirrors libgost.go's Start()).
	loadCfg := *cfg
	loadCfg.Services = nil
	if err := loader.Load(&loadCfg); err != nil {
		fmt.Fprintf(os.Stderr, "load config: %v\n", err)
		os.Exit(1)
	}

	var svcs []service.Service
	for _, svcCfg := range cfg.Services {
		svc, err := serviceparser.ParseService(svcCfg)
		if err != nil {
			fmt.Fprintf(os.Stderr, "parse service %q: %v\n", svcCfg.Name, err)
			os.Exit(1)
		}
		svcs = append(svcs, svc)
		fmt.Printf("service %q listening on %s/%s\n", svcCfg.Name, svc.Addr().String(), svc.Addr().Network())
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	_ = ctx

	errCh := make(chan error, len(svcs))
	for _, svc := range svcs {
		go func(s service.Service) {
			errCh <- s.Serve()
		}(svc)
	}

	fmt.Println("gostcli running, press Ctrl+C to stop")
	for err := range errCh {
		if err != nil {
			fmt.Fprintf(os.Stderr, "service error: %v\n", err)
		}
	}
}
