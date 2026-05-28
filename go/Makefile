GO       := go
GOMOBILE := gomobile
MIN_MACOS := 11.0

.PHONY: all clean

all: libgost.a

# macOS universal binary (arm64 + amd64)
libgost.a: libgost_arm64.a libgost_amd64.a
	lipo libgost_arm64.a libgost_amd64.a -create -output libgost.a
	cp libgost_arm64.h libgost.h
	rm -f libgost_arm64.a libgost_arm64.h libgost_amd64.a libgost_amd64.h

libgost_arm64.a:
	CGO_CFLAGS="-mmacosx-version-min=$(MIN_MACOS)" CGO_LDFLAGS="-mmacosx-version-min=$(MIN_MACOS)" CGO_ENABLED=1 GOARCH=arm64 $(GO) build \
	  -buildmode=c-archive -buildvcs=false -trimpath --ldflags="-s -w" \
	  -o libgost_arm64.a ./cmd/macos

libgost_amd64.a:
	CGO_CFLAGS="-mmacosx-version-min=$(MIN_MACOS)" CGO_LDFLAGS="-mmacosx-version-min=$(MIN_MACOS)" CGO_ENABLED=1 GOARCH=amd64 $(GO) build \
	  -buildmode=c-archive -buildvcs=false -trimpath --ldflags="-s -w" \
	  -o libgost_amd64.a ./cmd/macos

# Android .aar via gomobile bind (targets ./gostlib directly)
# CGO_LDFLAGS passes -z max-page-size=16384 to the NDK linker so that LOAD
# segments in libgojni.so are aligned to 16KB, satisfying Android 15's
# requirement for devices with 16KB page size.
gostlib.aar:
	CGO_LDFLAGS="-Wl,-z,max-page-size=16384" GOFLAGS="-buildvcs=false" $(GOMOBILE) bind \
	  -target android/arm,android/arm64,android/amd64 \
	  -androidapi 26 \
	  -trimpath -ldflags="-s -w" \
	  -o gostlib.aar \
	  ./gostlib
	@if [ -d ../android/app/libs ]; then cp gostlib.aar ../android/app/libs/gostlib.aar; fi

clean:
	rm -f libgost.a libgost.h libgost_arm64.* libgost_amd64.* \
	      gostlib.aar gostlib-sources.jar
