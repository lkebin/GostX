.PHONY: all android android-release macos macos-release clean

all: android

android: libgost/libgost.aar
	cd android && ./gradlew assembleDebug

android-release: libgost/libgost.aar
	cd android && ./gradlew assembleRelease bundleRelease

macos: macos/Frameworks/Libgost.xcframework
	cd macos && xcodebuild -project GostX.xcodeproj -scheme GostX -configuration Release -derivedDataPath build ONLY_ACTIVE_ARCH=YES build

# Developer ID distribution: signed, notarized DMG for direct sharing
# Prerequisites:
#   1. Developer ID Application certificate in Keychain
#   2. (Optional) APPLE_ID, APPLE_PASSWORD, APPLE_TEAM env vars for notarization
#      APPLE_PASSWORD should be an app-specific password from appleid.apple.com
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

libgost/libgost.aar:
	cd libgost && $(MAKE) libgost.aar
	cp libgost/libgost.aar android/app/libs/libgost.aar

macos/Frameworks/Libgost.xcframework:
	cd libgost && $(MAKE) macos-xcframework
	mkdir -p macos/Frameworks
	rm -rf macos/Frameworks/Libgost.xcframework
	cp -R libgost/Libgost.xcframework macos/Frameworks/Libgost.xcframework
	@echo "Libgost.xcframework → macos/Frameworks/"

clean:
	cd libgost && $(MAKE) clean
	rm android/app/libs/libgost.aar
	cd android && ./gradlew clean 2>/dev/null || true
	rm -rf macos/Frameworks/Libgost.xcframework
	rm -rf macos/build
