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

.PHONY: debug-dmg release-dmg
debug-dmg release-dmg: TARGET = $(subst -dmg,,$@)
debug-dmg release-dmg:
	# xcodebuild will create GostX.app under build folder
	t="$(TARGET)" && t="`tr '[:lower:]' '[:upper:]' <<< $${t:0:1}`$${t:1}" \
	  && rm -rf build/$${t}/GostX/ \
	  && mkdir build/$${t}/GostX \
	  && cp -r build/$${t}/GostX.app build/$${t}/GostX/ \
	  && ln -s /Applications build/$${t}/GostX/Applications \
	  && hdiutil create build/$${t}/GostX.dmg -ov -volname "GostX" -fs HFS+ -srcfolder build/$${t}/GostX/ \
	  && rm -rf build/$${t}/GostX/

go/libgost.a:
	cd go && $(MAKE)

.PHONY: libgost-clean
libgost-clean:
	cd go && $(MAKE) clean

.PHONY: clean
clean: libgost-clean
	xcodebuild -project GostX.xcodeproj clean
