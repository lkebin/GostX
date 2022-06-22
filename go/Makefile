all: libgost_arm64.a libgost_amd64.a
	lipo libgost_arm64.a libgost_amd64.a -create -output libgost.a
	cp libgost_arm64.h libgost.h
	rm -rf libgost_arm64.* libgost_amd64.*

libgost_arm64.a:
	CGO_ENABLED=1 GOOS=darwin GOARCH=arm64 go build --buildmode=c-archive -trimpath --ldflags="-s -w" -o libgost_arm64.a

libgost_amd64.a:
	CGO_ENABLED=1 GOOS=darwin GOARCH=amd64 go build --buildmode=c-archive -trimpath --ldflags="-s -w" -o libgost_amd64.a

clean:
	rm -rf *.a *.h
