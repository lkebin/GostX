# libgost Makefile Refactoring

**Date:** 2026-07-02
**Status:** Draft

## Goal

Decouple libgost's Makefile from the Android project structure. libgost should only build the `.aar` artifact; the root Makefile (orchestrator) should handle copying it into the Android project.

## Current Problem

`libgost/Makefile` line 19 reaches upward into the Android directory:

```makefile
@if [ -d ../android/app/libs ]; then cp libgost.aar ../android/app/libs/libgost.aar; fi
```

This creates a reverse dependency: a library knows where its consumer lives.

## Design

### libgost/Makefile

Remove line 19. The `libgost.aar` target builds the artifact in `libgost/` only. No knowledge of Android.

### Root Makefile

Add a copy step to the `libgost/libgost.aar` target:

```makefile
libgost/libgost.aar:
	cd libgost && $(MAKE) libgost.aar
	cp libgost/libgost.aar android/app/libs/libgost.aar
```

This target already orchestrates the libgost build; adding the copy here keeps orchestration in one place.

### Unchanged

- `android/app/build.gradle.kts` — continues loading `.aar` from `libs/` via `fileTree`
- `debug-symbols` and `clean` targets in libgost/Makefile — no Android coupling
- Root `android` and `android-release` targets — depend on `libgost/libgost.aar` which now handles both build and copy

## Files Changed

| File | Change |
|------|--------|
| `libgost/Makefile` | Remove line 19 (copy to android) |
| `Makefile` (root) | Add `cp` after `$(MAKE) libgost.aar` |
