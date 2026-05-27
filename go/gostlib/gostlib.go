package gostlib

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"

	"github.com/go-gost/core/service"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/config/loader"
	serviceparser "github.com/go-gost/x/config/parsing/service"

	_ "github.com/go-gost/x/connector/direct"
	_ "github.com/go-gost/x/connector/http"
	_ "github.com/go-gost/x/connector/relay"
	_ "github.com/go-gost/x/connector/socks/v4"
	_ "github.com/go-gost/x/connector/socks/v5"
	_ "github.com/go-gost/x/connector/ss"
	_ "github.com/go-gost/x/connector/tcp"
	_ "github.com/go-gost/x/dialer/grpc"
	_ "github.com/go-gost/x/dialer/quic"
	_ "github.com/go-gost/x/dialer/ssh"
	_ "github.com/go-gost/x/dialer/tcp"
	_ "github.com/go-gost/x/dialer/tls"
	_ "github.com/go-gost/x/dialer/udp"
	_ "github.com/go-gost/x/dialer/ws"
	_ "github.com/go-gost/x/handler/forward/local"
	_ "github.com/go-gost/x/handler/forward/remote"
	_ "github.com/go-gost/x/handler/http"
	_ "github.com/go-gost/x/handler/redirect/tcp"
	_ "github.com/go-gost/x/handler/redirect/udp"
	_ "github.com/go-gost/x/handler/relay"
	_ "github.com/go-gost/x/handler/socks/v4"
	_ "github.com/go-gost/x/handler/socks/v5"
	_ "github.com/go-gost/x/handler/ss"
	_ "github.com/go-gost/x/handler/tun"
	_ "github.com/go-gost/x/listener/tcp"
	_ "github.com/go-gost/x/listener/tls"
	_ "github.com/go-gost/x/listener/udp"
	_ "github.com/go-gost/x/listener/ws"

	"gopkg.in/yaml.v3"
)

var (
	mu       sync.Mutex
	running  bool
	stopping bool
	services []service.Service
	cancelFn context.CancelFunc
	serveWg  sync.WaitGroup
)

var stateCond = sync.NewCond(&mu)

// VPNProxyAddr is the loopback SOCKS5 address auto-injected by StartVPNMode.
// tun2socks must be configured to forward traffic to this address.
const VPNProxyAddr = "127.0.0.1:10808"

// NOTE: services are not registered in gost's global ServiceRegistry.
// Cross-service references in YAML (e.g., relay handler → named service) are not supported.

// Start starts gost v3 with the given YAML configuration string.
func Start(yamlConfig string) error {
	mu.Lock()
	defer mu.Unlock()

	for stopping {
		stateCond.Wait()
	}
	if running {
		return fmt.Errorf("gost is already running; call Stop() first")
	}

	cfg := &config.Config{}
	if err := yaml.Unmarshal([]byte(yamlConfig), cfg); err != nil {
		return fmt.Errorf("invalid YAML config: %w", err)
	}
	ensureServiceNames(cfg)
	loadCfg := *cfg
	loadCfg.Services = nil
	if err := loader.Load(&loadCfg); err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	svcs, err := startServices(ctx, cfg)
	if err != nil {
		cancel()
		return err
	}

	cancelFn = cancel
	services = svcs
	running = true
	return nil
}

// StartVPNMode starts gost with an auto-injected internal SOCKS5 at 127.0.0.1:10808.
func StartVPNMode(yamlConfig string) error {
	cfg := &config.Config{}
	if err := yaml.Unmarshal([]byte(yamlConfig), cfg); err != nil {
		return fmt.Errorf("invalid YAML config: %w", err)
	}
	injectInternalSocks5(cfg)

	b, err := yaml.Marshal(cfg)
	if err != nil {
		return err
	}
	return Start(string(b))
}

// Stop stops all running gost services.
func Stop() error {
	mu.Lock()
	if !running {
		for stopping {
			stateCond.Wait()
		}
		mu.Unlock()
		return nil
	}

	stopping = true
	running = false
	services = nil
	cancel := cancelFn
	cancelFn = nil
	mu.Unlock()

	cancel() // guaranteed non-nil: cancelFn is always set before running=true

	serveWg.Wait()

	mu.Lock()
	stopping = false
	stateCond.Broadcast()
	mu.Unlock()
	return nil
}

// IsRunning returns true if gost is currently running.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return running
}

// GetStatus returns a JSON string containing running state and service addresses.
func GetStatus() string {
	mu.Lock()
	defer mu.Unlock()

	addrs := make([]string, 0, len(services))
	for _, svc := range services {
		if addr := svc.Addr(); addr != nil {
			addrs = append(addrs, addr.String())
		}
	}

	b, _ := json.Marshal(map[string]any{
		"running":   running,
		"addresses": addrs,
	})
	return string(b)
}

func startServices(ctx context.Context, cfg *config.Config) ([]service.Service, error) {
	svcs := make([]service.Service, 0, len(cfg.Services))
	for _, svcCfg := range cfg.Services {
		svc, err := serviceparser.ParseService(svcCfg)
		if err != nil {
			for _, s := range svcs {
				_ = s.Close()
			}
			return nil, fmt.Errorf("failed to parse service %q: %w", svcCfg.Name, err)
		}
		svcs = append(svcs, svc)
	}

	launchServices(ctx, svcs)
	return svcs, nil
}

func launchServices(ctx context.Context, svcs []service.Service) {
	for _, svc := range svcs {
		serveWg.Add(1)
		go func(s service.Service) {
			defer serveWg.Done()
			<-ctx.Done()
			_ = s.Close()
		}(svc)

		serveWg.Add(1)
		go func(s service.Service) {
			defer serveWg.Done()
			_ = s.Serve()
		}(svc)
	}
}

func ensureServiceNames(cfg *config.Config) {
	for i, svc := range cfg.Services {
		if svc != nil && svc.Name == "" {
			svc.Name = fmt.Sprintf("_gostx_service_%d", i)
		}
	}
}

func injectInternalSocks5(cfg *config.Config) {
	for _, svc := range cfg.Services {
		if svc != nil && svc.Addr == VPNProxyAddr {
			return
		}
	}

	cfg.Services = append(cfg.Services, &config.ServiceConfig{
		Name:     "_gostx_vpn_internal",
		Addr:     VPNProxyAddr,
		Handler:  &config.HandlerConfig{Type: "socks5"},
		Listener: &config.ListenerConfig{Type: "tcp"},
	})
}
