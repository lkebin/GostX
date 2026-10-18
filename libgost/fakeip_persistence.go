package libgost

import (
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"strings"

	"github.com/go-gost/x/config"
	"github.com/go-gost/x/resolver"
)

const fakeIPStateFile = "fakeip-state.json"

type fakeIPPersistence struct {
	allocator resolver.FakeIPAllocator
	path      string
}

// prepareFakeIPPersistence discovers fakeip nodes in DNS services, restores
// their shared allocator, and installs it for the DNS handler to reuse.
func prepareFakeIPPersistence(cfg *config.Config) (*fakeIPPersistence, error) {
	if cfg == nil {
		return nil, nil
	}

	var (
		inet4, inet6 netip.Prefix
		found        bool
		firstNode    string
	)
	for _, svc := range cfg.Services {
		if svc == nil || svc.Handler == nil || svc.Handler.Type != "dns" || svc.Forwarder == nil {
			continue
		}
		for _, node := range svc.Forwarder.Nodes {
			if node == nil {
				continue
			}
			nodeInet4, nodeInet6, ok := resolver.ParseFakeIPNode(strings.TrimSpace(node.Addr))
			if !ok {
				continue
			}
			if !found {
				inet4, inet6 = nodeInet4, nodeInet6
				firstNode = node.Addr
				found = true
				continue
			}
			if nodeInet4 != inet4 || nodeInet6 != inet6 {
				return nil, fmt.Errorf("fakeip node %q ranges (%s, %s) differ from node %q ranges (%s, %s)",
					node.Addr, nodeInet4, nodeInet6, firstNode, inet4, inet6)
			}
		}
	}
	if !found {
		return nil, nil
	}

	allocator := resolver.NewFakeIPAllocator(inet4, inet6)
	file, err := os.Open(fakeIPStateFile)
	if err != nil {
		if !os.IsNotExist(err) {
			return nil, fmt.Errorf("open fakeip state %q: %w", fakeIPStateFile, err)
		}
	} else {
		loadErr := allocator.Load(file)
		closeErr := file.Close()
		if loadErr != nil {
			return nil, fmt.Errorf("load fakeip state %q: %w", fakeIPStateFile, loadErr)
		}
		if closeErr != nil {
			return nil, fmt.Errorf("close fakeip state %q: %w", fakeIPStateFile, closeErr)
		}
	}

	resolver.SetFakeIPStore(allocator)
	return &fakeIPPersistence{allocator: allocator, path: fakeIPStateFile}, nil
}

// save exports the allocator to a temporary file and atomically replaces the
// previous state. The old state remains intact if any step fails.
func (p *fakeIPPersistence) save() error {
	if p == nil || p.allocator == nil {
		return nil
	}

	dir := filepath.Dir(p.path)
	base := filepath.Base(p.path)
	tmp, err := os.CreateTemp(dir, "."+base+".tmp-*")
	if err != nil {
		return fmt.Errorf("create fakeip state temporary file: %w", err)
	}
	tmpName := tmp.Name()
	keep := false
	defer func() {
		if !keep {
			_ = os.Remove(tmpName)
		}
	}()
	if err := tmp.Chmod(0600); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("set fakeip state temporary file permissions: %w", err)
	}
	if err := p.allocator.Store(tmp); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("store fakeip state: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("sync fakeip state temporary file: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("close fakeip state temporary file: %w", err)
	}
	if err := os.Rename(tmpName, p.path); err != nil {
		return fmt.Errorf("replace fakeip state: %w", err)
	}
	keep = true
	return nil
}

// close clears the process-wide store. SetFakeIPStore(nil) is idempotent, so
// repeated cleanup is safe.
func (p *fakeIPPersistence) close() error {
	resolver.SetFakeIPStore(nil)
	return nil
}
