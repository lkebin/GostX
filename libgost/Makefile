GO       := go
GOMOBILE := gomobile

.PHONY: all clean debug-symbols

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

# Build .aar with debug symbols for crash symbolication.
# Produces debug-symbols.zip (for Google Play upload); libgost_debug.aar is a
# temporary intermediate that is cleaned up at the end of this target.
debug-symbols:
	CGO_LDFLAGS="-Wl,-z,max-page-size=16384" GOFLAGS="-buildvcs=false" $(GOMOBILE) bind \
	  -target android/arm,android/arm64,android/amd64 \
	  -androidapi 26 \
	  -trimpath \
	  -o libgost_debug.aar \
	  .
	@rm -rf _debug_tmp && mkdir -p _debug_tmp
	@unzip -qo libgost_debug.aar -d _debug_tmp >/dev/null
	@NDK_LLVM=$(NDK_LLVM); \
	if [ -z "$$NDK_LLVM" ] && [ -n "$$ANDROID_NDK_HOME" ]; then \
	  NDK_LLVM="$$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$$(uname | tr 'A-Z' 'a-z')-x86_64/bin"; \
	fi; \
	OBJCOPY="$${NDK_LLVM}/llvm-objcopy"; \
	echo "Using objcopy: $$OBJCOPY"; \
	for so in _debug_tmp/jni/*/libgojni.so; do \
	  abi=$$(basename $$(dirname $$so)); \
	  echo "Stripping debug info from $$abi..."; \
	  "$$OBJCOPY" --only-keep-debug "$$so" "_debug_tmp/jni/$$abi/libgojni.so.dbg" 2>/dev/null || \
	    cp "$$so" "_debug_tmp/jni/$$abi/libgojni.so.dbg"; \
	  "$$OBJCOPY" --strip-debug "$$so" 2>/dev/null || true; \
	done
	@cd _debug_tmp && zip -qr ../debug-symbols.zip jni/
	@rm -f libgost_debug.aar
	@echo "Debug symbols written to debug-symbols.zip"

clean:
	rm -f libgost.aar libgost_debug.aar libgost-sources.jar debug-symbols.zip
	rm -rf _debug_tmp
