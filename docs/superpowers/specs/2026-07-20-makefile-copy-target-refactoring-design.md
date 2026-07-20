# Root Makefile: 构建产物复制目标重构

**Date:** 2026-07-20
**Status:** Draft

## Goal

优化根 Makefile 中 libgost 构建产物复制到平台依赖目录的逻辑：将 copy 操作固定在 libgost 构建完成之后，并通过 `.PHONY` 标记、`clean` 健壮性、target 名一致性三方面完善 Makefile。

## Changes

### 1. `.PHONY` 标记

当前第一行已包含所有顶层目标，无需修改：

```makefile
.PHONY: all android android-release macos macos-release clean
```

### 2. `clean` 健壮性

```makefile
# Before
clean:
	cd libgost && $(MAKE) clean
	rm android/app/libs/libgost.aar
	cd android && ./gradlew clean 2>/dev/null || true
	rm -rf macos/Frameworks/Libgost.xcframework
	rm -rf macos/build

# After
clean:
	cd libgost && $(MAKE) clean
	rm -f android/app/libs/libgost.aar
	cd android && ./gradlew clean 2>/dev/null || true
	rm -rf macos/Frameworks/Libgost.xcframework
	rm -rf macos/build
```

### 3. Target 名一致性

引入中间 `.PHONY` 构建目标，将 build（递归 make）与 copy（文件操作）分离，使 copy 目标的路径与实际产物路径一致：

```makefile
# Before
libgost/libgost.aar:
	cd libgost && $(MAKE) libgost.aar
	cp libgost/libgost.aar android/app/libs/libgost.aar

macos/Frameworks/Libgost.xcframework:
	cd libgost && $(MAKE) macos-xcframework
	mkdir -p macos/Frameworks
	rm -rf macos/Frameworks/Libgost.xcframework
	cp -R libgost/Libgost.xcframework macos/Frameworks/Libgost.xcframework

# After
FORCE:

.PHONY: build-libgost-aar build-libgost-xcframework

build-libgost-aar: FORCE
	cd libgost && $(MAKE) libgost.aar

build-libgost-xcframework: FORCE
	cd libgost && $(MAKE) macos-xcframework

android/app/libs/libgost.aar: build-libgost-aar
	cp libgost/libgost.aar $@

macos/Frameworks/Libgost.xcframework: build-libgost-xcframework
	mkdir -p $(@D)
	rm -rf $@
	cp -R libgost/Libgost.xcframework $@
```

**FORCE 的作用**：`build-libgost-aar` 和 `build-libgost-xcframework` 虽然是 `.PHONY`，但依赖 `FORCE` 确保任何一次 `make android` 都会传递给递归 make，由 libgost/Makefile 内部判断是否需要真正编译。

**调用链（`make android`）**：

```
make android
  → build-libgost-aar (FORCE → libgost Makefile 判断是否需要重新构建)
    → android/app/libs/libgost.aar (cp libgost 产物到 android/)
      → cd android && ./gradlew assembleDebug
```

**调用链（`make macos`）**：

```
make macos
  → build-libgost-xcframework (FORCE → libgost Makefile 判断是否需要重新构建)
    → macos/Frameworks/Libgost.xcframework (cp libgost 产物到 macos/)
      → xcodebuild
```

## 完整 Makefile 变更

```makefile
.PHONY: all android android-release macos macos-release clean build-libgost-aar build-libgost-xcframework

all: android

FORCE:

build-libgost-aar: FORCE
	cd libgost && $(MAKE) libgost.aar

build-libgost-xcframework: FORCE
	cd libgost && $(MAKE) macos-xcframework

android/app/libs/libgost.aar: build-libgost-aar
	cp libgost/libgost.aar $@

macos/Frameworks/Libgost.xcframework: build-libgost-xcframework
	mkdir -p $(@D)
	rm -rf $@
	cp -R libgost/Libgost.xcframework $@

android: android/app/libs/libgost.aar
	cd android && ./gradlew assembleDebug

android-release: android/app/libs/libgost.aar
	cd android && ./gradlew assembleRelease bundleRelease

macos: macos/Frameworks/Libgost.xcframework
	cd macos && xcodebuild -project GostX.xcodeproj -scheme GostX -configuration Release -derivedDataPath build ONLY_ACTIVE_ARCH=YES build

macos-release: macos/Frameworks/Libgost.xcframework
	cd macos && \
		xcodebuild archive \
			-project GostX.xcodeproj \
			-scheme GostX \
			-configuration Release \
			-archivePath build/GostX.xcarchive \
			-destination 'platform=macOS' && \
		xcodebuild -exportArchive \
			-archivePath build/GostX.xcarchive \
			-exportOptionsPlist Configuration/ExportOptions.plist \
			-exportPath build/Release && \
		hdiutil create -volname GostX \
			-srcfolder build/Release/GostX.app \
			-ov -format UDZO \
			build/GostX.dmg
	@echo "✅ DMG ready: macos/build/GostX.dmg"
	@if [ -n "$$APPLE_ID" ] && [ -n "$$APPLE_PASSWORD" ] && [ -n "$$APPLE_TEAM" ]; then \
		echo "📤 Submitting for notarization..."; \
		xcrun notarytool submit macos/build/GostX.dmg \
			--apple-id "$$APPLE_ID" \
			--password "$$APPLE_PASSWORD" \
			--team-id "$$APPLE_TEAM" \
			--wait && \
		xcrun stapler staple macos/build/GostX.dmg && \
		echo "✅ Notarized: macos/build/GostX.dmg"; \
	else \
		echo "⚠️  Skipping notarization (set APPLE_ID, APPLE_PASSWORD, APPLE_TEAM to enable)"; \
	fi

clean:
	cd libgost && $(MAKE) clean
	rm -f android/app/libs/libgost.aar
	cd android && ./gradlew clean 2>/dev/null || true
	rm -rf macos/Frameworks/Libgost.xcframework
	rm -rf macos/build
```
