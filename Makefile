.PHONY: all android android-release macos clean

all: android

android: libgost/libgost.aar
	cd android && ./gradlew assembleDebug

android-release: libgost/libgost.aar
	cd android && ./gradlew assembleRelease bundleRelease

macos: macos/Frameworks/Libgost.xcframework
	cd macos && xcodebuild -project GostX.xcodeproj -scheme GostX -configuration Release -derivedDataPath build build

libgost/libgost.aar:
	cd libgost && $(MAKE) libgost.aar

macos/Frameworks/Libgost.xcframework:
	cd libgost && $(MAKE) macos-xcframework
	mkdir -p macos/Frameworks
	rm -rf macos/Frameworks/Libgost.xcframework
	cp -R libgost/Libgost.xcframework macos/Frameworks/Libgost.xcframework
	@echo "Libgost.xcframework → macos/Frameworks/"

clean:
	cd libgost && $(MAKE) clean
	cd android && ./gradlew clean 2>/dev/null || true
	rm -rf macos/Frameworks/Libgost.xcframework
	rm -rf macos/build
