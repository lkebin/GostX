# Makefile macOS Target Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `macos` target to root Makefile that builds `Libgost.xcframework` via `libgost/Makefile`, copies it to `macos/Frameworks/`, then runs `xcodebuild`.

**Architecture:** `libgost/Makefile` only builds the artifact (`macos-xcframework` target). Root `Makefile` owns the full pipeline: build dependency → copy artifact → invoke Xcode. Same pattern as the existing `android` target → `libgost.aar`.

**Tech Stack:** GNU Make, gomobile, xcodebuild

## Global Constraints

- `libgost/Makefile` must NOT copy files outside `libgost/` directory
- Root `Makefile` handles all file copying to `macos/Frameworks/`
- Target name in libgost: `macos-xcframework` (renamed from `macos-framework`)
- Build flags for gomobile unchanged: `-tags with_gvisor -target macos -trimpath -ldflags="-s -w"`

---

### Task 1: Refactor libgost/Makefile — rename target and strip copy logic

**Files:**
- Modify: `libgost/Makefile`

**Interfaces:**
- Produces: `macos-xcframework` phony target — builds `Libgost.xcframework` in `libgost/` directory only

- [ ] **Step 1: Update `.PHONY` declaration**

Replace `macos-framework` with `macos-xcframework`.

In `libgost/Makefile`, line 4, change:

```makefile
.PHONY: all clean debug-symbols macos-framework
```

To:

```makefile
.PHONY: all clean debug-symbols macos-xcframework
```

- [ ] **Step 2: Rename target and remove copy/move logic**

Replace the `macos-framework` target (lines 53-63) with `macos-xcframework` that only builds, no copy.

Remove:
```makefile
macos-framework:
	CGO_ENABLED=1 GOFLAGS="-buildvcs=false" $(GOMOBILE) bind \
	  -tags with_gvisor \
	  -target macos \
	  -trimpath -ldflags="-s -w" \
	  -o Libgost.xcframework \
	  .
	mkdir -p ../macos/Frameworks
	rm -rf ../macos/Frameworks/Libgost.xcframework
	mv Libgost.xcframework ../macos/Frameworks/Libgost.xcframework
	@echo "Libgost.xcframework → macos/Frameworks/"
```

Add:
```makefile
macos-xcframework:
	CGO_ENABLED=1 GOFLAGS="-buildvcs=false" $(GOMOBILE) bind \
	  -tags with_gvisor \
	  -target macos \
	  -trimpath -ldflags="-s -w" \
	  -o Libgost.xcframework \
	  .
```

- [ ] **Step 3: Update `clean` target to remove cross-directory cleanup**

Replace the `clean` target (lines 65-68):

Remove:
```makefile
clean:
	rm -f libgost.aar libgost_debug.aar libgost-sources.jar debug-symbols.zip
	rm -rf _debug_tmp
	rm -rf ../macos/Frameworks/Libgost.xcframework
```

Add:
```makefile
clean:
	rm -f libgost.aar libgost_debug.aar libgost-sources.jar debug-symbols.zip Libgost.xcframework
	rm -rf _debug_tmp
```

- [ ] **Step 4: Commit**

```bash
git add libgost/Makefile
git commit -m "refactor: rename macos-framework to macos-xcframework, strip copy logic

- macos-xcframework only builds Libgost.xcframework in libgost/
- Copy responsibility moved to root Makefile
- clean no longer removes ../macos/Frameworks/

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: Add macos target and copy rule to root Makefile

**Files:**
- Modify: `Makefile`

**Interfaces:**
- Consumes: `macos-xcframework` phony target from `libgost/Makefile` (Task 1)
- Produces: `macos` phony target — builds framework, copies to `macos/Frameworks/`, runs xcodebuild

- [ ] **Step 1: Add framework build+copy rule**

In root `Makefile`, add after the `libgost/libgost.aar:` rule (after line 13):

```makefile
# macOS .xcframework via gomobile bind → libgost/Makefile
macos/Frameworks/Libgost.xcframework:
	cd libgost && $(MAKE) macos-xcframework
	mkdir -p macos/Frameworks
	rm -rf macos/Frameworks/Libgost.xcframework
	cp -R libgost/Libgost.xcframework macos/Frameworks/Libgost.xcframework
	@echo "Libgost.xcframework → macos/Frameworks/"
```

- [ ] **Step 2: Add `macos` target**

In root `Makefile`, add after the `android-release` target (after line 9):

```makefile
macos: macos/Frameworks/Libgost.xcframework
	cd macos && xcodebuild -project GostX.xcodeproj -scheme GostX -configuration Release build
```

- [ ] **Step 3: Update `.PHONY` to include `macos`**

In root `Makefile`, line 1, change:

```makefile
.PHONY: all android android-release clean
```

To:

```makefile
.PHONY: all android android-release macos clean
```

- [ ] **Step 4: Update `clean` target to remove macOS framework**

Replace the `clean` target (lines 14-17):

Remove:
```makefile
clean:
	cd libgost && $(MAKE) clean
	cd android && ./gradlew clean 2>/dev/null || true
```

Add:
```makefile
clean:
	cd libgost && $(MAKE) clean
	cd android && ./gradlew clean 2>/dev/null || true
	rm -rf macos/Frameworks/Libgost.xcframework
```

- [ ] **Step 5: Commit**

```bash
git add Makefile
git commit -m "feat: add macos target to root Makefile

- macos target builds Libgost.xcframework, copies to macos/Frameworks/, then runs xcodebuild
- mirrors android target pattern (build dep → copy → platform build)
- clean removes macos/Frameworks/Libgost.xcframework

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: Verify the changes

- [ ] **Step 1: Verify `make -n macos` shows correct dry-run output**

```bash
cd /Users/kbliu/Workspace/project/GostX && make -n macos 2>&1 | head -20
```

Expected: Shows `cd libgost && make macos-xcframework`, then `mkdir -p macos/Frameworks`, `rm -rf`, `cp -R`, then `xcodebuild` command. No errors.

- [ ] **Step 2: Verify `make macos-xcframework` builds successfully in libgost**

```bash
cd /Users/kbliu/Workspace/project/GostX/libgost && make macos-xcframework
```

Expected: `Libgost.xcframework` created in `libgost/` directory. No `mkdir`/`mv` output about `../macos/Frameworks`.

- [ ] **Step 3: Verify framework is not in macos/Frameworks after libgost build**

```bash
ls /Users/kbliu/Workspace/project/GostX/macos/Frameworks/Libgost.xcframework 2>&1
```

Expected: File not found (unless it already existed from a previous build).

- [ ] **Step 4: Verify `make clean` in libgost removes the built framework**

```bash
cd /Users/kbliu/Workspace/project/GostX/libgost && make clean && ls Libgost.xcframework 2>&1
```

Expected: `make clean` succeeds, `ls` reports "No such file or directory".

- [ ] **Step 5: Verify `make clean` in root removes macos/Frameworks**

```bash
cd /Users/kbliu/Workspace/project/GostX && make clean
```

Expected: Cleans libgost, android (if gradle available), and removes `macos/Frameworks/Libgost.xcframework`.

- [ ] **Step 6: Commit (if any fixes were needed)**

Only if changes were made during verification.
