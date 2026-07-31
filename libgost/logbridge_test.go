package libgost

import (
	"io"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	corelogger "github.com/go-gost/core/logger"
	xlogger "github.com/go-gost/x/logger"
)

// TestInstallLoggerDoesNotCrash is a regression guard for the Android startup
// crash caused by the old installLogrusHook().
//
// That function read the unexported "logger" field of go-gost/x's default
// logger via unsafe and reinterpreted it as **logrus.Entry. When go-gost/x
// replaced its logrus backend with log/slog (commit 219dd51) the field became
// a *slog.Logger, so the reinterpreted "entry.Logger" pointed at arbitrary
// memory and AddHook() wrote through it, killing the process with
// "unexpected fault address ... fatal error: fault".
//
// installLogger() must never reach into another package's internals.
// The child process is expected to exit cleanly.
func TestInstallLoggerDoesNotCrash(t *testing.T) {
	if os.Getenv("LIBGOST_HOOK_REPRO_CHILD") == "1" {
		corelogger.SetDefault(xlogger.NewLogger(xlogger.OutputOption(io.Discard)))
		installLogger()
		return
	}

	cmd := exec.Command(os.Args[0], "-test.run=TestInstallLoggerDoesNotCrash", "-test.v")
	cmd.Env = append(os.Environ(), "LIBGOST_HOOK_REPRO_CHILD=1")
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("installLogger crashed with a slog-backed default logger: %v\n%s", err, out)
	}
}

// TestInstallLoggerForwardsGostLogs verifies the logger still does its job:
// go-gost/x internal logs must land in logCh so the app UI and log file see them.
func TestInstallLoggerForwardsGostLogs(t *testing.T) {
	resetLogDrainForTest()
	t.Cleanup(resetLogDrainForTest)

	prev := getLogLevel()
	t.Cleanup(func() { SetLogLevel(prev) })
	SetLogLevel("info")

	// Twice, mimicking a stop → start cycle: every Start() calls loader.Load()
	// (which installs a fresh default logger) followed by installLogger().
	for range 2 {
		corelogger.SetDefault(xlogger.NewLogger(xlogger.OutputOption(io.Discard)))
		installLogger()
	}

	corelogger.Default().Infof("gost internal marker %d", 42)

	if !drainContains(t, "gost internal marker 42") {
		t.Fatal("go-gost/x log did not reach logCh")
	}
}

// TestSharedLoggerForwardsLibgostLogs verifies libgost's own logs (previously
// logrus, now the shared slog-backed logger) still reach logCh.
func TestSharedLoggerForwardsLibgostLogs(t *testing.T) {
	resetLogDrainForTest()
	t.Cleanup(resetLogDrainForTest)

	prev := getLogLevel()
	t.Cleanup(func() { SetLogLevel(prev) })
	SetLogLevel("info")

	log().Infof("libgost marker %d", 7)

	if !drainContains(t, "libgost marker 7") {
		t.Fatal("libgost log did not reach logCh")
	}
}

// TestSharedLoggerRespectsLevel verifies SetLogLevel gates the shared logger,
// including go-gost/x's default logger, which the old code could not do.
func TestSharedLoggerRespectsLevel(t *testing.T) {
	resetLogDrainForTest()
	t.Cleanup(resetLogDrainForTest)

	prev := getLogLevel()
	t.Cleanup(func() { SetLogLevel(prev) })

	SetLogLevel("error")
	log().Infof("info must be dropped")
	corelogger.Default().Infof("gost info must be dropped")
	if drainContains(t, "must be dropped") {
		t.Fatal("info-level message leaked while level=error")
	}

	// Raising the level at runtime must take effect without a restart.
	SetLogLevel("info")
	log().Infof("info must pass")
	if !drainContains(t, "info must pass") {
		t.Fatal("info-level message dropped while level=info")
	}
}

// drainContains drains logCh and reports whether any message contains want.
func drainContains(t *testing.T, want string) bool {
	t.Helper()
	found := false
	for {
		select {
		case msg := <-logCh:
			if strings.Contains(msg, want) {
				found = true
			}
		default:
			return found
		}
	}
}

// TestSetTimezone verifies the platform timezone is applied to log timestamps.
func TestSetTimezone(t *testing.T) {
	orig := time.Local
	t.Cleanup(func() { time.Local = orig })

	SetTimezone("Asia/Shanghai", 8*3600)

	if got := time.Local.String(); got != "Asia/Shanghai" {
		t.Errorf("time.Local = %q, want %q", got, "Asia/Shanghai")
	}
	if _, off := time.Now().Zone(); off != 8*3600 {
		t.Errorf("offset = %d, want %d", off, 8*3600)
	}
}

// TestSetTimezoneFallsBackToFixedOffset verifies timestamps stay correct when
// the zone database cannot resolve the name (e.g. tzdata unreadable).
func TestSetTimezoneFallsBackToFixedOffset(t *testing.T) {
	orig := time.Local
	t.Cleanup(func() { time.Local = orig })

	const want = 5*3600 + 1800 // Asia/Kathmandu, +05:45
	SetTimezone("No/SuchZone", want)

	if _, off := time.Now().Zone(); off != want {
		t.Errorf("offset = %d, want %d", off, want)
	}
}

// TestSetTimezoneIsIdempotent verifies repeated calls do not rewrite the
// process-global time.Local, which would race with logging goroutines.
func TestSetTimezoneIsIdempotent(t *testing.T) {
	orig := time.Local
	origApplied := appliedTZ.Load()
	t.Cleanup(func() {
		time.Local = orig
		if origApplied != nil {
			appliedTZ.Store(origApplied)
		}
	})

	SetTimezone("Asia/Shanghai", 8*3600)
	first := time.Local

	SetTimezone("Asia/Shanghai", 8*3600)
	if time.Local != first {
		t.Error("repeated SetTimezone with the same zone rewrote time.Local")
	}

	// A genuine change must still apply.
	SetTimezone("Asia/Tokyo", 9*3600)
	if time.Local == first {
		t.Error("SetTimezone ignored a changed zone")
	}
}
