.PHONY: all android android-release clean

all: android

android: libgost/libgost.aar
	cd android && ./gradlew assembleDebug

android-release: libgost/libgost.aar
	cd android && ./gradlew assembleRelease bundleRelease

libgost/libgost.aar:
	cd libgost && $(MAKE) libgost.aar
	cp libgost/libgost.aar android/app/libs/libgost.aar

.PHONY: clean
clean:
	cd libgost && $(MAKE) clean
	rm android/app/libs/libgost.aar
	cd android && ./gradlew clean 2>/dev/null || true
