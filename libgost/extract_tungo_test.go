package libgost

import (
	"testing"

	"github.com/go-gost/x/config"
)

// TestExtractTungoService verifies that the tungo service is pulled out of the
// service list (it is handled by the gVisor stack, not gost's service runner)
// and that its chain name is reported. Direct-domain DNS is handled by the
// configured dns service (policy DNS via fakeip-exclude), so no resolver name
// is extracted here.
func TestExtractTungoService(t *testing.T) {
	cfg := &config.Config{
		Services: []*config.ServiceConfig{
			{Name: "vpn", Handler: &config.HandlerConfig{Type: "tungo", Chain: "upstream"}},
			{Name: "dns", Handler: &config.HandlerConfig{Type: "dns"}},
		},
		Chains: []*config.ChainConfig{
			{Name: "upstream"},
		},
	}

	chainName, filtered := extractTungoService(cfg)
	if chainName != "upstream" {
		t.Fatalf("chainName = %q, want upstream", chainName)
	}
	if len(filtered.Services) != 1 {
		t.Fatalf("filtered services = %d, want 1 (tungo removed)", len(filtered.Services))
	}
	if filtered.Services[0].Name != "dns" {
		t.Fatalf("filtered service = %q, want dns", filtered.Services[0].Name)
	}

	// No tungo service -> empty chain name, all services preserved.
	noTungo := &config.Config{Services: []*config.ServiceConfig{{Name: "dns"}}}
	if cn, f := extractTungoService(noTungo); cn != "" || len(f.Services) != 1 {
		t.Fatalf("no-tungo: chainName=%q filtered=%d, want empty/1", cn, len(f.Services))
	}
}
