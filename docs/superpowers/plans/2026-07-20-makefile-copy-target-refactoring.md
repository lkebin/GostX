# Makefile Copy Target Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor root Makefile to use clean `.PHONY` build targets + real file copy targets, and make `clean` robust.

**Architecture:** Introduce intermediate `.PHONY` targets (`build-libgost-aar`, `build-libgost-xcframework`) that always delegate to libgost's recursive `$(MAKE)`. Platform targets depend on real file copy targets (`android/app/libs/libgost.aar`, `macos/Frameworks/Libgost.xcframework`) which depend on the `.PHONY` build targets.

**Tech Stack:** GNU Make

## Global Constraints

- `.PHONY` must include all build targets (existing + new intermediate targets)
- FORCE target must exist for `.PHONY` targets (though redundant with `.PHONY`, it documents intent)
- `clean` must use `rm -f` for `android/app/libs/libgost.aar`
- `macos/Frameworks/Libgost.xcframework` recipe must use `$(@D)` and `$@` automatic variables

---

### Task 1: Refactor root Makefile

**Files:**
- Modify: `Makefile` (entire file)

- [ ] **Step 1: Add `.PHONY` entries for new intermediate targets**

Add `build-libgost-aar` and `build-libgost-xcframework` to the `.PHONY` line at the top of the file:

```makefile
.PHONY: all android android-release macos macos-release clean build-libgost-aar build-libgost-xcframework
```

- [ ] **Step 2: Add FORCE and intermediate build targets**

After `.PHONY` line, add:

```makefile
FORCE:

build-libgost-aar: FORCE
	cd libgost && $(MAKE) libgost.aar

build-libgost-xcframework: FORCE
	cd libgost && $(MAKE) macos-xcframework
```

- [ ] **Step 3: Replace `libgost/libgost.aar` with copy target**

Replace:

```makefile
libgost/libgost.aar:
	cd libgost && $(MAKE) libgost.aar
	cp libgost/libgost.aar android/app/libs/libgost.aar
```

With:

```makefile
android/app/libs/libgost.aar: build-libgost-aar
	cp libgost/libgost.aar $@
```

- [ ] **Step 4: Replace `macos/Frameworks/Libgost.xcframework` build target with copy target**

Replace:

```makefile
macos/Frameworks/Libgost.xcframework:
	cd libgost && $(MAKE) macos-xcframework
	mkdir -p macos/Frameworks
	rm -rf macos/Frameworks/Libgost.xcframework
	cp -R libgost/Libgost.xcframework macos/Frameworks/Libgost.xcframework
	@echo "Libgost.xcframework → macos/Frameworks/"
```

With:

```makefile
macos/Frameworks/Libgost.xcframework: build-libgost-xcframework
	mkdir -p $(@D)
	rm -rf $@
	cp -R libgost/Libgost.xcframework $@
```

- [ ] **Step 5: Update platform target dependencies**

Change:

```makefile
android: libgost/libgost.aar
android-release: libgost/libgost.aar
macos: macos/Frameworks/Libgost.xcframework
macos-release: macos/Frameworks/Libgost.xcframework
```

To:

```makefile
android: android/app/libs/libgost.aar
android-release: android/app/libs/libgost.aar
macos: macos/Frameworks/Libgost.xcframework
macos-release: macos/Frameworks/Libgost.xcframework
```

(Note: `macos` and `macos-release` dependencies remain the same — the target was already named after the copy destination. `android` changes from `libgost/libgost.aar` to `android/app/libs/libgost.aar`.)

- [ ] **Step 6: Fix `clean` target**

Change:

```makefile
	rm android/app/libs/libgost.aar
```

To:

```makefile
	rm -f android/app/libs/libgost.aar
```

- [ ] **Step 7: Dry-run to verify Makefile syntax**

```bash
cd /Users/kbliu/Workspace/project/GostX && make -n android 2>&1 | head -20
```

Expected output: shows the build chain (build-libgost-aar → copy → gradle), no syntax errors.

```bash
cd /Users/kbliu/Workspace/project/GostX && make -n macos 2>&1 | head -20
```

Expected: shows build-libgost-xcframework → copy → xcodebuild, no syntax errors.

```bash
cd /Users/kbliu/Workspace/project/GostX && make -n clean 2>&1
```

Expected: shows clean commands, no errors.

- [ ] **Step 8: Commit**

```bash
git add Makefile
git commit -m "refactor: split Makefile build and copy targets, add .PHONY intermediates, fix clean robustness"
```
