package libgost

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"net"
	"os"
	"runtime"
	runtimeDebug "runtime/debug"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/go-gost/core/service"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/config/loader"
	serviceparser "github.com/go-gost/x/config/parsing/service"
	xdialer "github.com/go-gost/x/dialer"
	"github.com/go-gost/x/registry"
	"github.com/sirupsen/logrus"

	_ "github.com/go-gost/x/connector/direct"
	_ "github.com/go-gost/x/connector/http"
	_ "github.com/go-gost/x/connector/http2"
	_ "github.com/go-gost/x/connector/relay"
	_ "github.com/go-gost/x/connector/sni"
	_ "github.com/go-gost/x/connector/socks/v4"
	_ "github.com/go-gost/x/connector/socks/v5"
	_ "github.com/go-gost/x/connector/ss"
	_ "github.com/go-gost/x/connector/tcp"
	_ "github.com/go-gost/x/dialer/grpc"
	_ "github.com/go-gost/x/dialer/http2"
	_ "github.com/go-gost/x/dialer/http2/h2"
	_ "github.com/go-gost/x/dialer/http3"
	_ "github.com/go-gost/x/dialer/mws"
	_ "github.com/go-gost/x/dialer/obfs/http"
	_ "github.com/go-gost/x/dialer/obfs/tls"
	_ "github.com/go-gost/x/dialer/quic"
	_ "github.com/go-gost/x/dialer/ssh"
	_ "github.com/go-gost/x/dialer/tcp"
	_ "github.com/go-gost/x/dialer/tls"
	_ "github.com/go-gost/x/dialer/udp"
	_ "github.com/go-gost/x/dialer/ws"
	_ "github.com/go-gost/x/handler/dns"
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
	_ "github.com/go-gost/x/listener/dns"
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

	// vpnChainName is the gost chain name extracted from the tungo service in
	// the user's config. StartTun uses this to route gVisor TCP/UDP sessions
	// directly through the chain, with no extra proxy listening port.
	// Empty string means no tungo service found; StartGost returns an error.
	vpnChainName string

	// vpnDNSServiceAddr is the normalised listen address of the first DNS service
	// found in the config, e.g. "127.0.0.1:5353". Empty when none is configured.
	vpnDNSServiceAddr string

	// socketProtector is the Android VpnService protect() callback.
	// When set, every TCP/UDP socket created by gost's internal dialer calls
	// socketProtector.Protect(fd) before connect(), bypassing VPN routing.
	// This is the primary bypass mechanism, working on all Android devices.
	socketProtector SocketProtector
)

// SocketProtector is a callback interface implemented by Android's VpnService.
// Protect is called for every socket created by gost's upstream dialers,
// before connect() is called, so the socket bypasses VPN routing.
// The fd parameter is the raw Linux file descriptor of the socket.
// Returning false is logged but does not abort the connection attempt.
type SocketProtector interface {
	Protect(fd int64) bool
}

var stateCond = sync.NewCond(&mu)

// vpnDNSVirtualAddr is the virtual IP advertised to Android as the DNS server
// when a Gost DNS service is found in the config. It must fall within the VPN
// subnet (10.0.0.0/24) so that DNS queries are routed through the TUN fd.
// Value is TUN address (10.0.0.2) + 1.
const (
	vpnDNSVirtualAddr = "10.0.0.3"
	vpnDNSVirtualPort = 53
)

// NOTE: services are not registered in gost's global ServiceRegistry.
// Cross-service references in YAML (e.g., relay handler → named service) are not supported.

// Start starts gost v3 with the given YAML configuration string.
func Start(yamlConfig string) (err error) {
	mu.Lock()
	defer mu.Unlock()

	for stopping {
		stateCond.Wait()
	}
	if running {
		return fmt.Errorf("gost is already running; call StopGost() first")
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
	// loader.Load() calls corelogger.SetDefault() with a *logrusLogger.
	// Attach our hook now so gost internal logs also appear in the app UI.
	installLogrusHook()
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("panic in service startup: %v", r)
		}
	}()

	ctx, cancel := context.WithCancel(context.Background())
	svcs, err := startServices(ctx, cfg)
	if err != nil {
		cancel()
		return err
	}

	cancelFn = cancel
	services = svcs
	running = true
	logrus.Infof("gost started: services=%d chains=%d hops=%d bypasses=%d",
		len(cfg.Services), len(cfg.Chains), len(cfg.Hops), len(cfg.Bypasses))
	return nil
}

// normalizeDNSAddr replaces an empty or "0.0.0.0" host with "127.0.0.1" so
// that the gVisor handler can dial the DNS service on the loopback interface.
func normalizeDNSAddr(addr string) string {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return addr
	}
	if host == "" || host == "0.0.0.0" {
		host = "127.0.0.1"
	}
	return net.JoinHostPort(host, port)
}

// detectVPNDNSService returns the normalised address of the first service in
// cfg whose handler type is "dns", or "" if none is present.
func detectVPNDNSService(cfg *config.Config) string {
	for _, svc := range cfg.Services {
		if svc != nil && svc.Handler != nil && svc.Handler.Type == "dns" && svc.Addr != "" {
			return normalizeDNSAddr(svc.Addr)
		}
	}
	return ""
}

// StartGost parses the YAML config, loads chains/hops/bypasses into the gost
// registry, and starts service listeners (DNS, proxy, etc.). For VPN mode the
// config must also contain a tungo service — its chain name is stored so
// StartTun can route gVisor traffic through it later.
// systemDNS is a comma-separated list of system DNS server IPs (e.g. "192.168.1.1,8.8.8.8").
// When non-empty, any forwarder node with addr: "system" is replaced with an
// address derived from the next server in the list.
func StartGost(yamlConfig string, systemDNS string) (err error) {
	defer func() {
		if r := recover(); r != nil {
			buf := make([]byte, 4096)
			n := runtime.Stack(buf, false)
			err = fmt.Errorf("panic in StartGost: %v\n%s", r, buf[:n])
		}
	}()

	cfg := &config.Config{}
	if err := yaml.Unmarshal([]byte(yamlConfig), cfg); err != nil {
		return fmt.Errorf("invalid YAML config: %w", err)
	}

	if systemDNS != "" {
		servers := parseSystemDNS(systemDNS)
		resolveSystemDNSInConfig(cfg, servers)
	}

	chainName, filtered := extractTungoService(cfg)
	if chainName == "" {
		return fmt.Errorf("config must contain a tungo service for VPN mode")
	}

	dnsServiceAddr := detectVPNDNSService(cfg)

	mu.Lock()
	vpnChainName = chainName
	vpnDNSServiceAddr = dnsServiceAddr
	mu.Unlock()

	b, err := yaml.Marshal(filtered)
	if err != nil {
		return err
	}
	return Start(string(b))
}

// extractTungoService scans cfg for a service whose handler type is "tungo",
// removes it from the services list (we handle it via gVisor in StartTun),
// and returns its chain name plus the filtered config.
func extractTungoService(cfg *config.Config) (chainName string, filtered *config.Config) {
	filtered = new(config.Config)
	*filtered = *cfg
	filtered.Services = nil

	for _, svc := range cfg.Services {
		if svc != nil && svc.Handler != nil && svc.Handler.Type == "tungo" {
			if chainName == "" && svc.Handler.Chain != "" {
				chainName = svc.Handler.Chain
			}
			continue // skip – handled by the gVisor stack
		}
		filtered.Services = append(filtered.Services, svc)
	}
	return chainName, filtered
}

// Stop stops all running gost services.
func StopGost() error {
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
	svcs := services // save before nil'ing; Close them below to free ports
	services = nil
	cancel := cancelFn
	cancelFn = nil
	mu.Unlock()

	cancel() // guaranteed non-nil: cancelFn is always set before running=true

	// Close services synchronously so the DNS UDP port is freed before the
	// next Start() can try to bind to it. The context-cancellation goroutines
	// in launchServices also call Close() — service implementations must be
	// idempotent on Close().
	for _, s := range svcs {
		_ = s.Close()
	}

	// Wait for service goroutines with a timeout. Some service implementations
	// wait for active connections to drain inside Close(), which can take
	// arbitrarily long. The 5-second cap ensures StopGost() always returns so
	// the next Start() is never blocked indefinitely on stateCond.Wait().
	waitDone := make(chan struct{})
	go func() {
		serveWg.Wait()
		close(waitDone)
	}()
	select {
	case <-waitDone:
	case <-time.After(5 * time.Second):
	}

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

// GetVPNDNSAddr returns the virtual DNS server IP (vpnDNSVirtualAddr) to
// advertise to the Android VPN when a Gost DNS service has been detected in the
// active config, or "" when no DNS service is configured (fall back to 8.8.8.8).
func GetVPNDNSAddr() string {
	mu.Lock()
	defer mu.Unlock()
	if vpnDNSServiceAddr == "" {
		return ""
	}
	return vpnDNSVirtualAddr
}

// ValidateConfig checks yamlConfig for errors without starting any services.
// Returns an empty string if the config is valid, or a human-readable error
// message describing the first problem found.
//
// Checks performed (in order):
//  1. YAML syntax
//  2. Each service's handler and listener type is registered
//  3. Every chain reference names a chain that exists in the config
func ValidateConfig(yamlConfig string) string {
	cfg := &config.Config{}
	if err := yaml.Unmarshal([]byte(yamlConfig), cfg); err != nil {
		return fmt.Sprintf("YAML 解析错误: %v", err)
	}

	// internalTypes are handler/listener types implemented inside libgost
	// rather than via the go-gost registry; skip the registry lookup for them.
	internalTypes := map[string]bool{"tungo": true}

	for _, svc := range cfg.Services {
		if svc == nil {
			continue
		}
		if svc.Handler != nil && svc.Handler.Type != "" {
			if !internalTypes[svc.Handler.Type] && registry.HandlerRegistry().Get(svc.Handler.Type) == nil {
				return fmt.Sprintf("未知 handler 类型 %q (服务 %q)", svc.Handler.Type, svc.Name)
			}
		}
		if svc.Listener != nil && svc.Listener.Type != "" {
			if !internalTypes[svc.Listener.Type] && registry.ListenerRegistry().Get(svc.Listener.Type) == nil {
				return fmt.Sprintf("未知 listener 类型 %q (服务 %q)", svc.Listener.Type, svc.Name)
			}
		}
	}

	chainNames := make(map[string]bool, len(cfg.Chains))
	for _, ch := range cfg.Chains {
		if ch != nil {
			chainNames[ch.Name] = true
		}
	}
	for _, svc := range cfg.Services {
		if svc == nil || svc.Handler == nil {
			continue
		}
		if ref := svc.Handler.Chain; ref != "" && !chainNames[ref] {
			return fmt.Sprintf("服务 %q 引用了不存在的 chain: %q", svc.Name, ref)
		}
	}

	return ""
}

// GetStatus returns the running state, service addresses, and VPN connection counters.
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
		"running":     running,
		"addresses":   addrs,
		"tcpConns":    atomic.LoadInt64(&tcpConns),
		"udpConns":    atomic.LoadInt64(&udpConns),
		"failedConns": atomic.LoadInt64(&failedConns),
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
			_ = s // unused after refactor; Close is handled by StopGost
		}(svc)

		serveWg.Add(1)
		go func(s service.Service) {
			defer serveWg.Done()
			defer func() {
				if r := recover(); r != nil {
					logrus.Errorf("panic in service goroutine: %v", r)
				}
			}()
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

// SetMemoryLimit configures the Go runtime GC for mobile background use.
// enabled=true: aggressive GC (GOGC=20) + 50 MB soft heap limit.
// 30 MB was too small; 100 MB is too large and keeps the heap unnecessarily
// bloated on mobile. 50 MB balances full-system proxy workloads against
// memory pressure. During doze mode all connections are closed (see
// PauseTun/WakeTun), so the heap shrinks naturally at night.
// enabled=false: restore defaults so normal service mode is unaffected.
// Call with enabled=true when VPN starts, false when it stops.
func SetMemoryLimit(enabled bool) {
	const limit = 50 * 1024 * 1024
	if enabled {
		runtimeDebug.SetGCPercent(20)
		runtimeDebug.SetMemoryLimit(limit)
	} else {
		runtimeDebug.SetGCPercent(100)
		runtimeDebug.SetMemoryLimit(math.MaxInt64)
	}
}

// SetSocketProtector sets the VpnService.protect() callback. When set, every
// TCP/UDP socket created by gost's upstream dialers is protected before
// connect(), bypassing Android's VPN routing table. This is the primary
// bypass mechanism, working on all Android devices. Must be called before StartGost.
func SetSocketProtector(p SocketProtector) {
	mu.Lock()
	socketProtector = p
	mu.Unlock()
	if p != nil {
		// Wire the protect callback into gost-x's global dialer hook.
		xdialer.SetGlobalSocketControl(func(fd uintptr) {
			mu.Lock()
			sp := socketProtector
			mu.Unlock()
			if sp != nil && !sp.Protect(int64(fd)) {
				logrus.Warnf("VpnService.protect() failed for fd %d", fd)
			}
		})
		logrus.Info("VPN socket protector registered")
	} else {
		xdialer.SetGlobalSocketControl(nil)
		logrus.Info("VPN socket protector cleared")
	}
}

// SetWorkDir sets the process working directory so that relative file paths
// in gost configs (e.g. bypass.file.path: china_ip_list.txt) resolve against
// the given directory. Should be called once at service creation time with
// the app's external files directory.
func SetWorkDir(path string) error {
	return os.Chdir(path)
}

// parseSystemDNS splits a comma-separated list of DNS server IPs into a slice,
// trimming whitespace and discarding empty entries.
func parseSystemDNS(s string) []string {
	parts := strings.Split(s, ",")
	result := make([]string, 0, len(parts))
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			result = append(result, p)
		}
	}
	return result
}

// resolveSystemDNSInConfig replaces "system" placeholders in DNS forwarder
// node addresses with actual system DNS server IPs. Each "system" gets the
// next server from the list, cycling if there are more placeholders than servers.
// If servers is empty or nil, the config is left unchanged.
func resolveSystemDNSInConfig(cfg *config.Config, servers []string) {
	if len(servers) == 0 {
		return
	}
	logrus.Infof("system DNS servers: %v", servers)
	idx := 0
	for _, svc := range cfg.Services {
		if svc == nil || svc.Handler == nil || svc.Handler.Type != "dns" || svc.Forwarder == nil {
			continue
		}
		for _, node := range svc.Forwarder.Nodes {
			if node == nil || node.Addr != "system" {
				continue
			}
			ip := servers[idx%len(servers)]
			idx++
			if strings.Contains(ip, ":") {
				node.Addr = "udp://[" + ip + "]:53"
			} else {
				node.Addr = "udp://" + ip + ":53"
			}
			logrus.Infof("DNS forwarder %q: system → %s", node.Name, node.Addr)
		}
	}
}
