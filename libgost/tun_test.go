package libgost

import (
	"runtime"
	"testing"
)

func TestStopTunWhenNotStarted(t *testing.T) {
	// StopTun on an idle instance should be a no-op
	if err := StopTun(); err != nil {
		t.Fatalf("StopTun() on idle should not error: %v", err)
	}
}

func TestStartTunInvalidFd(t *testing.T) {
	// fd=-1 must return an error without panicking
	if err := StartTun(-1, 1500); err == nil {
		t.Fatal("StartTun(-1, 1500) should return an error")
	}
}

func TestGetTunnelFileDescriptor(t *testing.T) {
	if runtime.GOOS != "darwin" {
		t.Skip("GetTunnelFileDescriptor is darwin-only")
	}
	// When no utun is active (typical unit test env), returns -1 without error.
	fd := GetTunnelFileDescriptor()
	if fd != -1 {
		t.Logf("found existing utun fd: %d", fd)
	}
}
