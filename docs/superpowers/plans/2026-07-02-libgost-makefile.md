# libgost Makefile Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decouple libgost's Makefile from the Android project — libgost builds the `.aar` only, root Makefile handles copying it to `android/app/libs/`.

**Architecture:** Two-line change across two Makefiles. Remove the upward-reference copy from `libgost/Makefile`, add it as a downstream step in the root `Makefile`'s `libgost/libgost.aar` target.

**Tech Stack:** GNU Make

---

### Task 1: Remove Android copy from libgost/Makefile

**Files:**
- Modify: `libgost/Makefile`

- [ ] **Step 1: Remove line 19 (the android copy line)**

Delete this line from `libgost/Makefile`:
```makefile
	@if [ -d ../android/app/libs ]; then cp libgost.aar ../android/app/libs/libgost.aar; fi
```

The `libgost.aar` target becomes:
```makefile
libgost.aar:
	CGO_LDFLAGS="-Wl,-z,max-page-size=16384" GOFLAGS="-buildvcs=false" $(GOMOBILE) bind \
	  -target android/arm,android/arm64,android/amd64 \
	  -androidapi 26 \
	  -trimpath -ldflags="-s -w" \
	  -o libgost.aar \
	  .
```

- [ ] **Step 2: Verify the file looks correct**

```bash
cat -n libgost/Makefile
```

Expected: Line 19 is gone, no reference to `../android` remains.

- [ ] **Step 3: Commit**

```bash
git add libgost/Makefile
git commit -m "$(cat <<'EOF'
refactor: remove android copy from libgost Makefile

libgost should only build the .aar; the root Makefile now handles
copying it to android/app/libs/.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Add copy step to root Makefile

**Files:**
- Modify: `Makefile` (root)

- [ ] **Step 1: Add `cp` after the `$(MAKE)` line in the `libgost/libgost.aar` target**

Change the `libgost/libgost.aar` target from:
```makefile
libgost/libgost.aar:
	cd libgost && $(MAKE) libgost.aar
```

To:
```makefile
libgost/libgost.aar:
	cd libgost && $(MAKE) libgost.aar
	cp libgost/libgost.aar android/app/libs/libgost.aar
```

- [ ] **Step 2: Verify the file looks correct**

```bash
cat -n Makefile
```

Expected: The `libgost/libgost.aar` target has both the `$(MAKE)` and `cp` lines.

- [ ] **Step 3: Dry-run to verify the copy works**

```bash
# If libgost.aar already exists, just test the copy
cp libgost/libgost.aar android/app/libs/libgost.aar && echo "Copy OK"
```

Expected: "Copy OK"

- [ ] **Step 4: Commit**

```bash
git add Makefile
git commit -m "$(cat <<'EOF'
refactor: copy aar to android from root Makefile

The root Makefile now handles copying the built .aar into
android/app/libs/, instead of libgost reaching upward.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```
