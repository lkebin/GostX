package libgost

import (
	"strings"
	"testing"
)

const routesHeader = `
services:
  - name: vpn
    handler:
      type: tungo
      chain: upstream
      metadata:
`

// The macOS/iOS packet tunnel provider needs the explicit route list before it
// builds NEPacketTunnelNetworkSettings, i.e. before StartGost runs.
func TestVPNRoutes_String(t *testing.T) {
	yaml := routesHeader + "        routes: 192.168.2.22/32, 172.16.5.0/24\n"
	if got, want := VPNRoutes(yaml), "192.168.2.22/32,172.16.5.0/24"; got != want {
		t.Fatalf("VPNRoutes() = %q, want %q", got, want)
	}
}

func TestVPNRoutes_List(t *testing.T) {
	yaml := routesHeader + "        routes:\n          - 192.168.2.22/32\n          - 10.8.0.0/16\n"
	if got, want := VPNRoutes(yaml), "192.168.2.22/32,10.8.0.0/16"; got != want {
		t.Fatalf("VPNRoutes() = %q, want %q", got, want)
	}
}

// A bare address is treated as a single host; invalid entries are dropped so a
// typo cannot break the whole tunnel.
func TestVPNRoutes_Normalisation(t *testing.T) {
	yaml := routesHeader + "        routes: 192.168.2.22, nope, 172.16.5.0/24\n"
	if got, want := VPNRoutes(yaml), "192.168.2.22/32,172.16.5.0/24"; got != want {
		t.Fatalf("VPNRoutes() = %q, want %q", got, want)
	}
}

// route/routes are both accepted, also under the wg-prefixed spelling used by
// the dialer metadata.
func TestVPNRoutes_KeyAliases(t *testing.T) {
	for _, key := range []string{"routes", "route", "vpn.routes"} {
		yaml := routesHeader + "        " + key + ": 192.168.2.22/32\n"
		if got := VPNRoutes(yaml); got != "192.168.2.22/32" {
			t.Fatalf("VPNRoutes() with key %q = %q", key, got)
		}
	}
}

func TestVPNRoutes_Empty(t *testing.T) {
	for _, yaml := range []string{
		"",
		"services: []\n",
		routesHeader, // no routes key
		"services:\n  - name: vpn\n    handler:\n      type: dns\n",
		"not: [valid: yaml",
	} {
		if got := VPNRoutes(yaml); got != "" {
			t.Fatalf("VPNRoutes(%q) = %q, want empty", yaml, got)
		}
	}
}

func TestVPNRoutes_IgnoresNonTungoServices(t *testing.T) {
	yaml := `
services:
  - name: socks
    addr: 127.0.0.1:11080
    handler:
      type: socks5
      metadata:
        routes: 10.0.0.0/8
  - name: vpn
    handler:
      type: tungo
      chain: upstream
      metadata:
        routes: 192.168.2.22/32
`
	got := VPNRoutes(yaml)
	if got != "192.168.2.22/32" {
		t.Fatalf("VPNRoutes() = %q, want only the tungo service's route", got)
	}
	if strings.Contains(got, "10.0.0.0") {
		t.Fatalf("routes from a non-tungo service leaked into the result: %q", got)
	}
}
