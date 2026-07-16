# Makefile macOS Target Design

## Overview

Refactor the macOS build flow in Makefiles to follow the same pattern as Android: root Makefile owns the full build pipeline (build dependency → copy artifact → invoke platform build tool), while `libgost/Makefile` is only responsible for producing the artifact.

## Changes

### libgost/Makefile

#### Rename `macos-framework` → `macos-xcframework`

Strip the copy/move logic. This target only builds `Libgost.xcframework` in the `libgost/` directory.

```makefile
macos-xcframework:
    CGO_ENABLED=1 GOFLAGS="-buildvcs=false" $(GOMOBILE) bind \
      -tags with_gvisor \
      -target macos \
      -trimpath -ldflags="-s -w" \
      -o Libgost.xcframework \
      .
```

**Removed lines:** `mkdir -p ../macos/Frameworks`, `rm -rf ../macos/Frameworks/Libgost.xcframework`, `mv Libgost.xcframework ../macos/Frameworks/Libgost.xcframework`.

#### Update `.PHONY`

Replace `macos-framework` with `macos-xcframework`.

#### Update `clean`

Remove `rm -rf ../macos/Frameworks/Libgost.xcframework`. Only clean artifacts within `libgost/`:
```makefile
clean:
    rm -f libgost.aar libgost_debug.aar libgost-sources.jar debug-symbols.zip Libgost.xcframework
    rm -rf _debug_tmp
```

### Root Makefile

#### Add `macos` target

```makefile
macos: macos/Frameworks/Libgost.xcframework
    cd macos && xcodebuild -project GostX.xcodeproj -scheme GostX -configuration Release build
```

#### Add framework build+copy rule

```makefile
macos/Frameworks/Libgost.xcframework:
    cd libgost && $(MAKE) macos-xcframework
    mkdir -p macos/Frameworks
    rm -rf macos/Frameworks/Libgost.xcframework
    cp -R libgost/Libgost.xcframework macos/Frameworks/Libgost.xcframework
    @echo "Libgost.xcframework → macos/Frameworks/"
```

#### Update `clean`

Add macOS framework cleanup:
```makefile
clean:
    cd libgost && $(MAKE) clean
    cd android && ./gradlew clean 2>/dev/null || true
    rm -rf macos/Frameworks/Libgost.xcframework
```

## Target Comparison

| | Android | macOS (new) |
|---|---|---|
| libgost build target | `libgost.aar` | `macos-xcframework` |
| Artifact location | `libgost/libgost.aar` | `libgost/Libgost.xcframework` |
| Root target | `android` | `macos` |
| Copy destination | `android/app/libs/` | `macos/Frameworks/` |
| Platform build | `./gradlew assembleDebug` | `xcodebuild … build` |
| Clean | `./gradlew clean` | (removes `macos/Frameworks/`) |
