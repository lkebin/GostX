OTHER_LDFLAGS := -L./go/tor-static/tor/ -ltor \
				 -L./go/tor-static/zlib/dist/lib -lz \
				 -L./go/tor-static/libevent/dist/lib -levent \
				 -L./go/tor-static/openssl/dist/lib -lssl -lcrypto \
				 -L./go/tor-static/xz/dist/lib -llzma

.PHONY: all
all: debug

.PHONY: debug
debug: go/libgost.a
	xcodebuild OTHER_LDFLAGS="$(OTHER_LDFLAGS)" -project GostX.xcodeproj -target GostX -configuration Debug

.PHONY: release
release: go/libgost.a
	xcodebuild OTHER_LDFLAGS="$(OTHER_LDFLAGS)" -project GostX.xcodeproj -target GostX -configuration Release 

.PHONY: dmg
dmg:
	# xcodebuild will create GostX.app under build/Release folder
	rm -rf build/Release/GostX/
	mkdir build/Release/GostX
	cp -r build/Release/GostX.app build/Release/GostX/
	ln -s /Applications build/Release/GostX/Applications
	hdiutil create build/Release/GostX.dmg -ov -volname "GostX" -fs HFS+ -srcfolder build/Release/GostX/
	rm -rf build/Release/GostX/

go/libgost.a:
	cd go && $(MAKE)

.PHONY: libgost-clean
libgost-clean:
	cd go && $(MAKE) clean

.PHONY: clean
clean: libgost-clean
	xcodebuild -project GostX.xcodeproj clean
