package libgost

import (
	"errors"
	"io"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/go-gost/x/config"
	"github.com/go-gost/x/resolver"
)

func configWithFakeIP() *config.Config {
	return &config.Config{
		Services: []*config.ServiceConfig{{
			Handler: &config.HandlerConfig{Type: "dns"},
			Forwarder: &config.ForwarderConfig{
				Nodes: []*config.ForwardNodeConfig{{
					Addr: "fakeip://?inet4=198.18.0.0/15&inet6=fc00::/18",
				}},
			},
		}},
	}
}

func TestPrepareFakeIPPersistenceMissingFile(t *testing.T) {
	t.Chdir(t.TempDir())
	resolver.SetFakeIPStore(nil)
	t.Cleanup(func() { resolver.SetFakeIPStore(nil) })

	p, err := prepareFakeIPPersistence(configWithFakeIP())
	if err != nil {
		t.Fatal(err)
	}
	if p == nil || p.allocator == nil {
		t.Fatal("fakeip persistence should be prepared")
	}
	if _, err := os.Stat(fakeIPStateFile); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("state file should not be created during load: %v", err)
	}
	if resolver.GetFakeIPStore() != p.allocator {
		t.Fatal("prepared allocator should be installed")
	}
}

func TestPrepareFakeIPPersistenceNoFakeIP(t *testing.T) {
	t.Chdir(t.TempDir())
	resolver.SetFakeIPStore(nil)
	t.Cleanup(func() { resolver.SetFakeIPStore(nil) })

	cfg := &config.Config{Services: []*config.ServiceConfig{{
		Handler:   &config.HandlerConfig{Type: "dns"},
		Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Addr: "udp://127.0.0.1:53"}}},
	}}}
	p, err := prepareFakeIPPersistence(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if p != nil {
		t.Fatal("persistence should not be prepared without fakeip")
	}
	if resolver.GetFakeIPStore() != nil {
		t.Fatal("store should remain unset without fakeip")
	}
}

func TestPrepareFakeIPPersistenceBadJSON(t *testing.T) {
	t.Chdir(t.TempDir())
	resolver.SetFakeIPStore(nil)
	t.Cleanup(func() { resolver.SetFakeIPStore(nil) })
	if err := os.WriteFile(fakeIPStateFile, []byte("{"), 0600); err != nil {
		t.Fatal(err)
	}

	p, err := prepareFakeIPPersistence(configWithFakeIP())
	if err == nil || p != nil {
		t.Fatalf("bad state should fail before installing store: p=%v err=%v", p, err)
	}
	if resolver.GetFakeIPStore() != nil {
		t.Fatal("store should remain unset after load failure")
	}
}

func TestPrepareFakeIPPersistencePrefixMismatch(t *testing.T) {
	t.Chdir(t.TempDir())
	resolver.SetFakeIPStore(nil)
	t.Cleanup(func() { resolver.SetFakeIPStore(nil) })
	cfg := configWithFakeIP()
	cfg.Services = append(cfg.Services, &config.ServiceConfig{
		Handler: &config.HandlerConfig{Type: "dns"},
		Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{
			Addr: "fakeip://?inet4=198.19.0.0/16&inet6=fc00::/18",
		}}},
	})

	p, err := prepareFakeIPPersistence(cfg)
	if err == nil || p != nil || !strings.Contains(err.Error(), "fakeip") {
		t.Fatalf("prefix mismatch should fail: p=%v err=%v", p, err)
	}
	if resolver.GetFakeIPStore() != nil {
		t.Fatal("store should remain unset after config failure")
	}
}

func TestStartGostFakeIPPersistencePoolMismatchLeavesNoStore(t *testing.T) {
	resetTestState(t)
	t.Chdir(t.TempDir())
	resolver.SetFakeIPStore(nil)
	t.Cleanup(func() { resolver.SetFakeIPStore(nil) })

	other := resolver.NewFakeIPAllocator(
		netip.MustParsePrefix("198.19.0.0/16"),
		netip.MustParsePrefix("fc00::/18"),
	)
	file, err := os.Create(fakeIPStateFile)
	if err != nil {
		t.Fatal(err)
	}
	if err := other.Store(file); err != nil {
		file.Close()
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}

	if err := StartGost(testVPNFakeIPYAML, ""); err == nil {
		t.Fatal("StartGost should reject a state file with a different address pool")
	}
	if IsRunning() {
		t.Fatal("pool mismatch must prevent service startup")
	}
	if resolver.GetFakeIPStore() != nil {
		t.Fatal("pool mismatch must not leave a global fakeip store")
	}
}

func TestFakeIPPersistenceSaveAndClose(t *testing.T) {
	t.Chdir(t.TempDir())
	resolver.SetFakeIPStore(nil)
	t.Cleanup(func() { resolver.SetFakeIPStore(nil) })
	p, err := prepareFakeIPPersistence(configWithFakeIP())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := p.allocator.Create("example.com", false); err != nil {
		t.Fatal(err)
	}
	if err := p.save(); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(fakeIPStateFile)
	if err != nil {
		t.Fatal(err)
	}
	if got := info.Mode().Perm(); got != 0600 {
		t.Fatalf("state file mode = %o, want 600", got)
	}
	if err := p.close(); err != nil {
		t.Fatal(err)
	}
	if resolver.GetFakeIPStore() != nil {
		t.Fatal("close should clear global store")
	}
	p2, err := prepareFakeIPPersistence(configWithFakeIP())
	if err != nil {
		t.Fatal(err)
	}
	defer p2.close()
	if domain, ok := p2.allocator.Lookup(netip.MustParseAddr("198.18.0.1")); !ok || domain != "example.com" {
		t.Fatalf("restored Lookup = (%q, %v)", domain, ok)
	}
}

type failingFakeIPAllocator struct{}

func (failingFakeIPAllocator) Lookup(netip.Addr) (string, bool) { return "", false }
func (failingFakeIPAllocator) Contains(netip.Addr) bool         { return false }
func (failingFakeIPAllocator) Create(string, bool) (netip.Addr, error) {
	return netip.Addr{}, errors.New("not supported")
}
func (failingFakeIPAllocator) Load(io.Reader) error  { return nil }
func (failingFakeIPAllocator) Store(io.Writer) error { return errors.New("store failed") }
func (failingFakeIPAllocator) Ranges() (netip.Prefix, netip.Prefix) {
	return netip.MustParsePrefix("198.18.0.0/15"), netip.MustParsePrefix("fc00::/18")
}

func TestStopGostSaveFailureStillCleansUp(t *testing.T) {
	resetTestState(t)
	t.Chdir(t.TempDir())
	resolver.SetFakeIPStore(nil)
	t.Cleanup(func() { resolver.SetFakeIPStore(nil) })

	if err := StartGost(testVPNFakeIPYAML, ""); err != nil {
		t.Fatalf("StartGost() failed: %v", err)
	}
	if err := os.Mkdir(fakeIPStateFile, 0700); err != nil {
		t.Fatal(err)
	}
	stopErr := StopGost()
	if stopErr == nil || !strings.Contains(stopErr.Error(), "replace fakeip state") {
		t.Fatalf("StopGost save error = %v, want deterministic rename error", stopErr)
	}
	if IsRunning() {
		t.Fatal("StopGost save failure must still clear running state")
	}
	if resolver.GetFakeIPStore() != nil {
		t.Fatal("StopGost save failure must still clear the global fakeip store")
	}
	mu.Lock()
	gotState, gotStopping := fakeIPState, stopping
	mu.Unlock()
	if gotState != nil {
		t.Fatal("StopGost save failure must clear fakeIPState")
	}
	if gotStopping {
		t.Fatal("StopGost save failure must clear stopping")
	}
	if err := os.RemoveAll(fakeIPStateFile); err != nil {
		t.Fatal(err)
	}

	if err := StartGost(testVPNFakeIPYAML, ""); err != nil {
		t.Fatalf("subsequent StartGost() failed: %v", err)
	}
	if !IsRunning() || resolver.GetFakeIPStore() == nil {
		t.Fatal("subsequent StartGost() did not restore running fakeip state")
	}
	if err := StopGost(); err != nil {
		t.Fatalf("subsequent StopGost() failed: %v", err)
	}
	if resolver.GetFakeIPStore() != nil || IsRunning() {
		t.Fatal("subsequent cleanup left fakeip services active")
	}
}

func TestFakeIPPersistenceSavePreservesOldStateOnError(t *testing.T) {
	t.Chdir(t.TempDir())
	oldState := []byte("old valid state")
	if err := os.WriteFile(fakeIPStateFile, oldState, 0600); err != nil {
		t.Fatal(err)
	}
	p := &fakeIPPersistence{allocator: failingFakeIPAllocator{}, path: fakeIPStateFile}
	if err := p.save(); err == nil {
		t.Fatal("save should fail")
	}
	got, err := os.ReadFile(fakeIPStateFile)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(oldState) {
		t.Fatalf("old state was replaced: %q", got)
	}
	matches, err := filepath.Glob("." + fakeIPStateFile + ".tmp-*")
	if err != nil {
		t.Fatal(err)
	}
	if len(matches) != 0 {
		t.Fatalf("temporary state files remain: %v", matches)
	}
}
