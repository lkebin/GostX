GO       := go
GOMOBILE := gomobile

.PHONY: all clean

all: libgost.aar

# Android .aar via gomobile bind (targets . directly)
# CGO_LDFLAGS passes -z max-page-size=16384 to the NDK linker so that LOAD
# segments in libgojni.so are aligned to 16KB, satisfying Android 15's
# requirement for devices with 16KB page size.
libgost.aar:
	CGO_LDFLAGS="-Wl,-z,max-page-size=16384" GOFLAGS="-buildvcs=false" $(GOMOBILE) bind \
	  -target android/arm,android/arm64,android/amd64 \
	  -androidapi 26 \
	  -trimpath -ldflags="-s -w" \
	  -o libgost.aar \
	  .
	@if [ -d ../android/app/libs ]; then cp libgost.aar ../android/app/libs/libgost.aar; fi

clean:
	rm -f libgost.aar libgost-sources.jar
