package libgost

import (
	"testing"
)

// testVPNWGProfileYAML is a VPN profile that sends the whitelisted targets
// through a WireGuard node and lets everything else go direct.
const testVPNWGProfileYAML = `
bypasses:
  - name: wg-ips
    whitelist: true
    matchers:
      - 10.8.0.0/16
chains:
  - name: vpn-chain
    hops:
      - name: wg-hop
        bypass: wg-ips
        nodes:
          - name: wg-node
            dialer:
              type: wg
              metadata:
                privateKey: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
                peerPublicKey: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
                endpoint: wg.example.com:51820
                localIPs: 10.7.0.2/32
            connector:
              type: wg
services:
  - name: _test_tungo
    handler:
      type: tungo
      chain: vpn-chain
  - name: test-socks5
    addr: 127.0.0.1:19080
    handler:
      type: socks5
    listener:
      type: tcp`

func TestValidateConfig_WGProfile(t *testing.T) {
	if got := ValidateConfig(testVPNWGProfileYAML); got != "" {
		t.Fatalf("ValidateConfig() = %q, want empty", got)
	}
}

func TestStartGost_WGProfile(t *testing.T) {
	resetTestState(t)
	if err := StartGost(testVPNWGProfileYAML, ""); err != nil {
		t.Fatalf("StartGost() failed: %v", err)
	}
	defer StopGost()
	waitForRunning(t)
}
