//go:build darwin

package libgost

import "testing"

func TestGetTunnelFileDescriptor(t *testing.T) {
	// When no utun is active (typical unit test env), returns -1 without error.
	fd := GetTunnelFileDescriptor()
	if fd != -1 {
		t.Logf("found existing utun fd: %d", fd)
	}
}
