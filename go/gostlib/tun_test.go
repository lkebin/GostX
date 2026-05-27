package gostlib

import "testing"

func TestStopVPNWhenNotStarted(t *testing.T) {
	// StopVPN on an idle instance should be a no-op
	if err := StopVPN(); err != nil {
		t.Fatalf("StopVPN() on idle should not error: %v", err)
	}
}

func TestStartVPNInvalidFd(t *testing.T) {
	// fd=-1 must return an error without panicking
	if err := StartVPN(-1, 1500); err == nil {
		t.Fatal("StartVPN(-1, 1500) should return an error")
	}
}
