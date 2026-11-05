.PHONY: all android android-release macos macos-release ios ios-sim ios-release clean \
	apple-lib build-libgost-aar build-libgost-xcframework icon icon-check

all: android

FORCE:

# Version stamp shared with Android: Android derives versionCode from epoch
# minutes and versionName from an exact git tag (falling back to the branch
# name, which App Store Connect would reject, so iOS falls back to 1.0.0).
APPLE_MARKETING_VERSION = $(shell git describe --tags --exact-match 2>/dev/null | sed 's/^v//' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$$' || echo 1.0.0)
APPLE_BUILD_NUMBER = $(shell echo $$(($$(date +%s) / 60)))

# Regenerate the per-platform ring icon from icon/ring.svg (Android
# VectorDrawable + Apple asset catalogs). Run after editing the source SVG.
icon:
	python3 tools/gen-icons.py

# Fails when a generated icon no longer matches icon/ring.svg.
icon-check:
	python3 tools/gen-icons.py --check

build-libgost-aar: FORCE
	cd libgost && $(MAKE) libgost.aar

build-libgost-xcframework: FORCE
	cd libgost && $(MAKE) apple-xcframework

apple-lib: build-libgost-xcframework
	mkdir -p apple/Frameworks
	rm -rf apple/Frameworks/Libgost.xcframework
	cp -R libgost/Libgost.xcframework apple/Frameworks/

android: android/app/libs/libgost.aar
	cd android && ./gradlew assembleDebug

android-release: android/app/libs/libgost.aar
	cd android && ./gradlew assembleRelease bundleRelease

macos: apple/Frameworks/Libgost.xcframework
	cd apple && xcodebuild -project GostX.xcodeproj -scheme GostX-macOS \
		-configuration Release -derivedDataPath build ONLY_ACTIVE_ARCH=YES \
		-allowProvisioningUpdates build

# Developer ID distribution: signed, notarized DMG for direct sharing
# Prerequisites:
#   1. Developer ID Application certificate in Keychain
#   2. (Optional) APPLE_ID, APPLE_PASSWORD, APPLE_TEAM env vars for notarization
#      APPLE_PASSWORD should be an app-specific password from appleid.apple.com
macos-release: apple/Frameworks/Libgost.xcframework
	cd apple && \
		xcodebuild archive \
			-project GostX.xcodeproj \
			-scheme GostX-macOS \
			-configuration Release \
			-archivePath build/GostX-macOS.xcarchive \
			-destination 'platform=macOS' && \
		xcodebuild -exportArchive \
			-archivePath build/GostX-macOS.xcarchive \
			-exportOptionsPlist Configuration/ExportOptions.plist \
			-exportPath build/Release && \
		hdiutil create -volname GostX \
			-srcfolder build/Release/GostX.app \
			-ov -format UDZO \
			build/GostX.dmg
	@echo "✅ DMG ready: apple/build/GostX.dmg"
	@if [ -n "$$APPLE_ID" ] && [ -n "$$APPLE_PASSWORD" ] && [ -n "$$APPLE_TEAM" ]; then \
		echo "📤 Submitting for notarization..."; \
		xcrun notarytool submit apple/build/GostX.dmg \
			--apple-id "$$APPLE_ID" \
			--password "$$APPLE_PASSWORD" \
			--team-id "$$APPLE_TEAM" \
			--wait && \
		xcrun stapler staple apple/build/GostX.dmg && \
		echo "✅ Notarized: apple/build/GostX.dmg"; \
	else \
		echo "⚠️  Skipping notarization (set APPLE_ID, APPLE_PASSWORD, APPLE_TEAM to enable)"; \
	fi

ios: apple/Frameworks/Libgost.xcframework
	cd apple && xcodebuild -project GostX.xcodeproj -scheme GostX-iOS \
		-configuration Debug -destination 'generic/platform=iOS' \
		-derivedDataPath build -allowProvisioningUpdates build \
		MARKETING_VERSION=$(APPLE_MARKETING_VERSION) \
		CURRENT_PROJECT_VERSION=$(APPLE_BUILD_NUMBER)

ios-sim: apple/Frameworks/Libgost.xcframework
	cd apple && xcodebuild -project GostX.xcodeproj -scheme GostX-iOS \
		-configuration Debug -destination 'generic/platform=iOS Simulator' \
		-derivedDataPath build build

ios-release: apple/Frameworks/Libgost.xcframework
	cd apple && \
		xcodebuild archive \
			-project GostX.xcodeproj \
			-scheme GostX-iOS \
			-configuration Release \
			-archivePath build/GostX-iOS.xcarchive \
			-destination 'generic/platform=iOS' \
			MARKETING_VERSION=$(APPLE_MARKETING_VERSION) \
			CURRENT_PROJECT_VERSION=$(APPLE_BUILD_NUMBER) && \
		xcodebuild -exportArchive \
			-archivePath build/GostX-iOS.xcarchive \
			-exportOptionsPlist Configuration/ExportOptions-appstore.plist \
			-exportPath build/Release-ios

android/app/libs/libgost.aar: build-libgost-aar
	cp libgost/libgost.aar $@

apple/Frameworks/Libgost.xcframework: build-libgost-xcframework
	mkdir -p $(@D)
	rm -rf $@
	cp -R libgost/Libgost.xcframework $@

clean:
	cd libgost && $(MAKE) clean
	cd android && ./gradlew clean 2>/dev/null || true
	rm -f android/app/libs/libgost.aar
	rm -rf apple/Frameworks/Libgost.xcframework
	rm -rf apple/build