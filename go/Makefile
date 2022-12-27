GO_ARM64_ROOT := ${PWD}/build/go-arm64
GO_AMD64_ROOT := ${PWD}/build/go-amd64
GO_ARM64 := $(GO_ARM64_ROOT)/bin/go
GO_AMD64 := $(GO_AMD64_ROOT)/bin/go

.PHONY: all
all: libgost_arm64.a libgost_amd64.a
	lipo libgost_arm64.a libgost_amd64.a -create -output libgost.a
	cp libgost_arm64.h libgost.h
	rm -rf libgost_arm64.* libgost_amd64.*

libgost_arm64.a: build/tor-static
	GOROOT=$(GO_ARM64_ROOT) CGO_CFLAGS="-mmacosx-version-min=11.0" GOARCH=arm64 arch -arm64 $(GO_ARM64) build --buildmode=c-archive -trimpath --ldflags="-s -w" -o libgost_arm64.a

libgost_amd64.a: build/tor-static
	GOROOT=$(GO_AMD64_ROOT) CGO_CFLAGS="-mmacosx-version-min=11.0" GOARCH=amd64 arch -x86_64 $(GO_AMD64) build --buildmode=c-archive -trimpath --ldflags="-s -w" -o libgost_amd64.a

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

build/tor-static/arm64: build/go-arm64
	rm -rf build/tor-static/arm64 && mkdir -p build/tor-static/arm64 && cp -r tor-static/ build/tor-static/arm64/
	cd build/tor-static/arm64 \
		&& GOROOT=$(GO_ARM64_ROOT) GOARCH=arm64 arch -arm64 $(GO_ARM64) run build.go build-all

build/tor-static/amd64: build/go-amd64
	rm -rf build/tor-static/amd64 && mkdir -p build/tor-static/amd64 && cp -r tor-static/ build/tor-static/amd64/
	cd build/tor-static/amd64 \
		&& GOROOT=$(GO_AMD64_ROOT) GOARCH=amd64 arch -x86_64 $(GO_AMD64) run build.go build-all

build/go-amd64: build
	[ -f build/go1.18.darwin-amd64.tar.gz ] || curl "https://dl.google.com/go/go1.18.darwin-amd64.tar.gz" --output build/go1.18.darwin-amd64.tar.gz
	cd build \
		&& tar -zxf go1.18.darwin-amd64.tar.gz \
		&& rm -rf go-amd64 \
		&& mv go go-amd64

build/go-arm64: build
	[ -f build/go1.18.darwin-arm64.tar.gz ] || curl "https://dl.google.com/go/go1.18.darwin-arm64.tar.gz" --output build/go1.18.darwin-arm64.tar.gz
	cd build \
		&& tar -zxf go1.18.darwin-arm64.tar.gz \
		&& rm -rf go-arm64 \
		&& mv go go-arm64

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
