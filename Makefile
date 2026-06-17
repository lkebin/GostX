.PHONY: all android android-release clean

all: android

android: go/gostlib.aar
	cd android && ./gradlew assembleDebug

android-release: go/gostlib.aar
	cd android && ./gradlew assembleRelease bundleRelease

go/gostlib.aar:
	cd go && $(MAKE) gostlib.aar

.PHONY: clean
clean:
	cd go && $(MAKE) clean
	cd android && ./gradlew clean 2>/dev/null || true
