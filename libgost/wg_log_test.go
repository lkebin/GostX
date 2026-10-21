package libgost

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	gostchain "github.com/go-gost/core/chain"
	"github.com/go-gost/x/registry"
)

// testWGLogProfileYAML points the wg node at a literal endpoint (no DNS) so
// the tunnel device is created without needing a real peer.
const testWGLogProfileYAML = `
bypasses:
  - name: via-wg
    whitelist: true
    matchers:
      - 10.8.0.0/16
chains:
  - name: upstream
    hops:
      - nodes:
          - name: wg-node
            bypass: via-wg
            dialer:
              type: wg
              metadata:
                privateKey: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
                peerPublicKey: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
                endpoint: 127.0.0.1:51820
                localIPs: 172.16.5.10/32
                allowedIPs: 10.8.0.0/16
            connector:
              type: wg
services:
  - name: _test_tungo
    handler:
      type: tungo
      chain: upstream`

// The wg dialer is constructed during loader.Load(), which installs its own
// default logger (stderr only) before parsing components. Dialer logs must
// still reach the application log, otherwise a broken tunnel is invisible in
// the in-app log view.
func TestWGDialerLogsReachAppLogFile(t *testing.T) {
	resetTestState(t)
	resetLogDrainForTest()

	logPath := filepath.Join(t.TempDir(), "gost-test.log")
	if err := SetLogFile(logPath); err != nil {
		t.Fatalf("SetLogFile: %v", err)
	}
	SetLogLevel("info")
	SetLoggingEnabled(true)
	t.Cleanup(func() { SetLogLevel("off") })

	if err := StartGost(testWGLogProfileYAML, ""); err != nil {
		t.Fatalf("StartGost: %v", err)
	}
	defer StopGost()

	ch := registry.ChainRegistry().Get("upstream")
	if ch == nil {
		t.Fatal("chain upstream not registered")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 700*time.Millisecond)
	defer cancel()

	// The whitelist (10.8.0.0/16) must select the wg node; Dial then creates
	// the tunnel device, which logs "wg: tunnel up".
	rt := ch.(gostchain.Chainer).Route(ctx, "tcp", "10.8.1.5:443")
	if rt == nil || len(rt.Nodes()) == 0 {
		t.Fatal("route is empty: the wg node was not selected")
	}
	_, _ = rt.Dial(ctx, "tcp", "10.8.1.5:443") // no real peer: the netstack dial times out

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if b, err := os.ReadFile(logPath); err == nil && strings.Contains(string(b), "wg: tunnel up") {
			return // the dialer's log reached the application log
		}
		time.Sleep(50 * time.Millisecond)
	}

	b, _ := os.ReadFile(logPath)
	t.Fatalf("app log never received the wg dialer's startup line; log contents:\n%s", b)
}
