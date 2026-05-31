package gostlib

import (
	"context"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	coreservice "github.com/go-gost/core/service"
)

const testYAML = `
services:
  - name: test-socks5
    addr: 127.0.0.1:19080
    handler:
      type: socks5
    listener:
      type: tcp`

// DNS service listens on a fixed loopback port.  Port 15353 must be free on the test host.
const testDNSYAML = `
services:
  - name: test-socks5
    addr: 127.0.0.1:19080
    handler:
      type: socks5
    listener:
      type: tcp
  - name: test-dns
    addr: 127.0.0.1:15353
    handler:
      type: dns
      metadata:
        dns: udp://8.8.8.8
    listener:
      type: dns
      metadata:
        mode: udp`

// testDNSYAMLNoHost uses an unqualified address to exercise normalisation.
const testDNSYAMLNoHost = `
services:
  - name: test-dns
    addr: :15353
    handler:
      type: dns
      metadata:
        dns: udp://8.8.8.8
    listener:
      type: dns
      metadata:
        mode: udp`

type fakeService struct {
	addr          net.Addr
	serveStarted  chan struct{}
	closeObserved chan struct{}
	finishServe   chan struct{}
	closeCh       chan struct{}
	closeOnce     sync.Once
	closeCount    atomic.Int32
}

func newFakeService() *fakeService {
	return &fakeService{
		addr:          &net.TCPAddr{IP: net.ParseIP("127.0.0.1"), Port: 29999},
		serveStarted:  make(chan struct{}),
		closeObserved: make(chan struct{}),
		finishServe:   make(chan struct{}),
		closeCh:       make(chan struct{}),
	}
}

func (s *fakeService) Serve() error {
	close(s.serveStarted)
	<-s.closeCh
	close(s.closeObserved)
	<-s.finishServe
	return nil
}

func (s *fakeService) Addr() net.Addr {
	return s.addr
}

func (s *fakeService) Close() error {
	s.closeCount.Add(1)
	s.closeOnce.Do(func() {
		close(s.closeCh)
	})
	return nil
}

func resetTestState(t *testing.T) {
	t.Helper()
	_ = Stop()
	mu.Lock()
	services = nil
	running = false
	stopping = false
	cancelFn = nil
	vpnChainName = ""
	vpnDNSServiceAddr = ""
	mu.Unlock()
}

func waitForRunning(t *testing.T) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if IsRunning() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("IsRunning() did not become true")
}

func TestStartStop(t *testing.T) {
	resetTestState(t)

	if err := Start(testYAML); err != nil {
		t.Fatalf("Start() failed: %v", err)
	}
	waitForRunning(t)

	if !IsRunning() {
		t.Fatal("IsRunning() should be true after Start()")
	}

	if err := Stop(); err != nil {
		t.Fatalf("Stop() failed: %v", err)
	}

	if IsRunning() {
		t.Fatal("IsRunning() should be false after Stop()")
	}
}

func TestDoubleStart(t *testing.T) {
	resetTestState(t)

	if err := Start(testYAML); err != nil {
		t.Fatalf("first Start() failed unexpectedly: %v", err)
	}
	defer Stop()

	err := Start(testYAML)
	if err == nil {
		t.Fatal("second Start() should return an error")
	}
}

func TestGetStatus(t *testing.T) {
	resetTestState(t)

	if err := Start(testYAML); err != nil {
		t.Fatalf("Start() failed: %v", err)
	}
	defer Stop()
	waitForRunning(t)

	raw := GetStatus()
	var info map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &info); err != nil {
		t.Fatalf("GetStatus() returned invalid JSON: %v — got: %s", err, raw)
	}
	if running, ok := info["running"].(bool); !ok || !running {
		t.Fatalf("expected running=true in status, got: %s", raw)
	}
}

func TestInvalidYAML(t *testing.T) {
	resetTestState(t)

	err := Start("not: valid: yaml: [[")
	if err == nil {
		t.Fatal("Start() should return error for invalid YAML")
	}
}

func TestStartVPNModeInjectsService(t *testing.T) {
	resetTestState(t)

	if err := StartVPNMode(testYAML); err != nil {
		t.Fatalf("StartVPNMode() failed: %v", err)
	}
	defer Stop()
	waitForRunning(t)

	if !IsRunning() {
		t.Fatal("IsRunning() should be true after StartVPNMode()")
	}

	conn, err := net.DialTimeout("tcp", "127.0.0.1:10808", time.Second)
	if err != nil {
		t.Fatalf("internal SOCKS5 listener not reachable: %v", err)
	}
	defer conn.Close()

	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		t.Fatalf("failed to write SOCKS5 greeting: %v", err)
	}

	resp := make([]byte, 2)
	if _, err := conn.Read(resp); err != nil {
		t.Fatalf("failed to read SOCKS5 greeting response: %v", err)
	}
	if resp[0] != 0x05 || resp[1] != 0x00 {
		t.Fatalf("unexpected SOCKS5 greeting response: %v", resp)
	}
}

func TestStopThenRestart(t *testing.T) {
	resetTestState(t)

	if err := Start(testYAML); err != nil {
		t.Fatalf("first Start() failed: %v", err)
	}
	waitForRunning(t)

	if err := Stop(); err != nil {
		t.Fatalf("first Stop() failed: %v", err)
	}
	if IsRunning() {
		t.Fatal("IsRunning() should be false after Stop()")
	}

	if err := Start(testYAML); err != nil {
		t.Fatalf("second Start() failed: %v", err)
	}
	defer Stop()
	waitForRunning(t)
}

func TestStartWaitsForStopToFinish(t *testing.T) {
	resetTestState(t)

	fake := newFakeService()
	ctx, cancel := context.WithCancel(context.Background())
	svcs := []coreservice.Service{fake}
	launchServices(ctx, svcs)

	mu.Lock()
	services = svcs
	running = true
	cancelFn = cancel
	mu.Unlock()

	<-fake.serveStarted

	stopErrCh := make(chan error, 1)
	go func() {
		stopErrCh <- Stop()
	}()

	<-fake.closeObserved

	startErrCh := make(chan error, 1)
	go func() {
		startErrCh <- Start(testYAML)
	}()

	select {
	case err := <-startErrCh:
		t.Fatalf("Start() returned before Stop() completed: %v", err)
	default:
	}

	close(fake.finishServe)

	select {
	case err := <-stopErrCh:
		if err != nil {
			t.Fatalf("Stop() failed: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("Stop() did not return after fake service exited")
	}

	select {
	case err := <-startErrCh:
		if err != nil {
			t.Fatalf("Start() failed after Stop() completed: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("Start() did not resume after Stop() completed")
	}
	defer Stop()
	waitForRunning(t)
}

func TestStopWaitsForServeToExit(t *testing.T) {
	resetTestState(t)

	fake := newFakeService()
	ctx, cancel := context.WithCancel(context.Background())
	svcs := []coreservice.Service{fake}
	launchServices(ctx, svcs)

	mu.Lock()
	services = svcs
	running = true
	cancelFn = cancel
	mu.Unlock()

	<-fake.serveStarted

	errCh := make(chan error, 1)
	done := make(chan struct{})
	go func() {
		errCh <- Stop()
		close(done)
	}()

	<-fake.closeObserved
	select {
	case <-done:
		t.Fatal("Stop() returned before Serve() exited")
	default:
	}

	close(fake.finishServe)

	select {
	case err := <-errCh:
		if err != nil {
			t.Fatalf("Stop() failed: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("Stop() did not return after Serve() exited")
	}

	if got := fake.closeCount.Load(); got != 1 {
		t.Fatalf("Close() called %d times, want 1", got)
	}
}

func TestNormalizeDNSAddr(t *testing.T) {
	cases := []struct{ input, want string }{
		{":5353", "127.0.0.1:5353"},
		{"0.0.0.0:5353", "127.0.0.1:5353"},
		{"127.0.0.1:5353", "127.0.0.1:5353"},
		{"192.168.1.1:5353", "192.168.1.1:5353"},
	}
	for _, c := range cases {
		got := normalizeDNSAddr(c.input)
		if got != c.want {
			t.Errorf("normalizeDNSAddr(%q) = %q, want %q", c.input, got, c.want)
		}
	}
}

func TestGetVPNDNSAddrNoService(t *testing.T) {
	resetTestState(t)
	if err := StartVPNMode(testYAML); err != nil {
		t.Fatalf("StartVPNMode() failed: %v", err)
	}
	defer Stop()
	waitForRunning(t)

	if got := GetVPNDNSAddr(); got != "" {
		t.Fatalf("GetVPNDNSAddr() = %q, want empty when no DNS service", got)
	}
}

func TestGetVPNDNSAddrWithService(t *testing.T) {
	resetTestState(t)
	if err := StartVPNMode(testDNSYAML); err != nil {
		t.Fatalf("StartVPNMode() failed: %v", err)
	}
	defer Stop()
	waitForRunning(t)

	if got := GetVPNDNSAddr(); got != vpnDNSVirtualAddr {
		t.Fatalf("GetVPNDNSAddr() = %q, want %q", got, vpnDNSVirtualAddr)
	}
}

func TestGetVPNDNSAddrNormalisesHost(t *testing.T) {
	resetTestState(t)
	if err := StartVPNMode(testDNSYAMLNoHost); err != nil {
		t.Fatalf("StartVPNMode() failed: %v", err)
	}
	defer Stop()
	waitForRunning(t)

	// Service addr was ":15353"; vpnDNSServiceAddr must be "127.0.0.1:15353".
	mu.Lock()
	got := vpnDNSServiceAddr
	mu.Unlock()
	if got != "127.0.0.1:15353" {
		t.Fatalf("vpnDNSServiceAddr = %q, want %q", got, "127.0.0.1:15353")
	}
	// GetVPNDNSAddr must still return the virtual IP.
	if addr := GetVPNDNSAddr(); addr != vpnDNSVirtualAddr {
		t.Fatalf("GetVPNDNSAddr() = %q, want %q", addr, vpnDNSVirtualAddr)
	}
}

func TestSetWorkDir(t *testing.T) {
	orig, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = os.Chdir(orig) }()

	tmp, err := os.MkdirTemp("", "workdir_test")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmp)

	if err := SetWorkDir(tmp); err != nil {
		t.Fatalf("SetWorkDir(%q) failed: %v", tmp, err)
	}

	got, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	// Resolve symlinks: macOS /var -> /private/var
	wantResolved, _ := filepath.EvalSymlinks(tmp)
	gotResolved, _ := filepath.EvalSymlinks(got)
	if wantResolved != gotResolved {
		t.Errorf("after SetWorkDir: Getwd() = %q, want %q", gotResolved, wantResolved)
	}
}
