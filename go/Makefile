GO := $(PWD)/build/go/bin/go
TARGET_ARM64 := arm64-apple-macos11
TARGET_AMD64 := x86_64-apple-macos11

.PHONY: all
all: libgost_arm64.a libgost_amd64.a
	lipo libgost_arm64.a libgost_amd64.a -create -output libgost.a
	cp libgost_arm64.h libgost.h
	rm -rf libgost_arm64.* libgost_amd64.*

libgost_arm64.a: build/tor-static
	CGO_CFLAGS="-mmacosx-version-min=11.0" CGO_ENABLED=1 GOARCH=arm64 $(GO) build --buildmode=c-archive -trimpath --ldflags="-s -w" -o libgost_arm64.a

libgost_amd64.a: build/tor-static
	CGO_CFLAGS="-mmacosx-version-min=11.0" CGO_ENABLED=1 GOARCH=amd64 $(GO) build --buildmode=c-archive -trimpath --ldflags="-s -w" -o libgost_amd64.a

build/tor-static: build/tor-static/arm64 build/tor-static/amd64
	# libevent
	[ -d tor-static/libevent/dist/lib ] || mkdir -p tor-static/libevent/dist/lib
	for f in build/tor-static/amd64/libevent/dist/lib/*.a; do \
		lipo build/tor-static/amd64/libevent/dist/lib/$${f##*/} \
			 build/tor-static/arm64/libevent/dist/lib/$${f##*/} \
			 -create -output tor-static/libevent/dist/lib/$${f##*/}; \
	done
	# zlib
	[ -d tor-static/zlib/dist/lib ] || mkdir -p tor-static/zlib/dist/lib
	for f in build/tor-static/amd64/zlib/dist/lib/*.a; do \
		lipo build/tor-static/amd64/zlib/dist/lib/$${f##*/} \
			 build/tor-static/arm64/zlib/dist/lib/$${f##*/} \
			 -create -output tor-static/zlib/dist/lib/$${f##*/}; \
	done
	# openssl
	[ -d tor-static/openssl/dist/lib ] || mkdir -p tor-static/openssl/dist/lib
	for f in build/tor-static/amd64/openssl/dist/lib/*.a; do \
		lipo build/tor-static/amd64/openssl/dist/lib/$${f##*/} \
			 build/tor-static/arm64/openssl/dist/lib/$${f##*/} \
			 -create -output tor-static/openssl/dist/lib/$${f##*/}; \
	done
	# xz
	[ -d tor-static/xz/dist/lib ] || mkdir -p tor-static/xz/dist/lib
	for f in build/tor-static/amd64/xz/dist/lib/*.a; do \
		lipo build/tor-static/amd64/xz/dist/lib/$${f##*/} \
			 build/tor-static/arm64/xz/dist/lib/$${f##*/} \
			 -create -output tor-static/xz/dist/lib/$${f##*/}; \
	done
	# tor
	lipo build/tor-static/amd64/tor/libtor.a \
		 build/tor-static/arm64/tor/libtor.a \
		 -create -output tor-static/tor/libtor.a

build/tor-static/arm64: build/go
	rm -rf build/tor-static/arm64 && mkdir -p build/tor-static/arm64 && cp -r tor-static/ build/tor-static/arm64/
	cd build/tor-static/arm64 \
		&& CFLAGS="-target $(TARGET_ARM64)" $(GO) run build.go -verbose -host="$(TARGET_ARM64)" build-openssl \
		&& CFLAGS="-target $(TARGET_ARM64)" $(GO) run build.go -verbose -host="$(TARGET_ARM64)" build-libevent \
		&& CFLAGS="-target $(TARGET_ARM64)" $(GO) run build.go -verbose -host="$(TARGET_ARM64)" build-zlib \
		&& CFLAGS="-target $(TARGET_ARM64)" $(GO) run build.go -verbose -host="$(TARGET_ARM64)" build-xz \
		&& CFLAGS="-target $(TARGET_ARM64)" $(GO) run build.go -verbose -host="$(TARGET_ARM64)" build-tor

build/tor-static/amd64: build/go
	rm -rf build/tor-static/amd64 && mkdir -p build/tor-static/amd64 && cp -r tor-static/ build/tor-static/amd64/
	cd build/tor-static/amd64 \
		&& CFLAGS="-target $(TARGET_AMD64)" $(GO) run build.go -verbose -host="$(TARGET_AMD64)" build-openssl \
		&& CFLAGS="-target $(TARGET_AMD64)" $(GO) run build.go -verbose -host="$(TARGET_AMD64)" build-libevent \
		&& CFLAGS="-target $(TARGET_AMD64)" $(GO) run build.go -verbose -host="$(TARGET_AMD64)" build-zlib \
		&& CFLAGS="-target $(TARGET_AMD64)" $(GO) run build.go -verbose -host="$(TARGET_AMD64)" build-xz \
		&& CFLAGS="-target $(TARGET_AMD64)" $(GO) run build.go -verbose -host="$(TARGET_AMD64)" build-tor
		
build/go: build
ifeq ($(shell uname -m),arm64)
	[ -f build/go.tar.gz ] || curl "https://dl.google.com/go/go1.18.darwin-arm64.tar.gz" --output build/go.tar.gz
else
	[ -f build/go.tar.gz ] || curl "https://dl.google.com/go/go1.18.darwin-amd64.tar.gz" --output build/go.tar.gz
endif
	cd build \
		&& tar -zxf go.tar.gz \
		&& $(GO) version

build:
	[ -d build ] || mkdir build

.PHONY: clean
clean:
	rm -rf build *.a *.h
	rm -rf tor-static/libevent/dist
	rm -rf tor-static/zlib/dist
	rm -rf tor-static/xz/dist
	rm -rf tor-static/openssl/dist
	rm -rf tor-static/tor/libtor.a
