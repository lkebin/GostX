.PHONY: all
all: debug

.PHONY: debug
debug: go/libgost.a
	xcodebuild -scheme GostX -project macos/GostX.xcodeproj -configuration Debug -derivedDataPath ./build

.PHONY: release
release: go/libgost.a
	xcodebuild -scheme GostX -project macos/GostX.xcodeproj -configuration Release -derivedDataPath ./build

.PHONY: debug-dmg release-dmg
debug-dmg release-dmg: TARGET = $(subst -dmg,,$@)
debug-dmg release-dmg:
	# xcodebuild will create GostX.app under build folder
	t="$(TARGET)" && t="`tr '[:lower:]' '[:upper:]' <<< $${t:0:1}`$${t:1}" \
	  && rm -rf build/Build/Products/$${t}/GostX/ \
	  && mkdir build/Build/Products/$${t}/GostX \
	  && cp -r build/Build/Products/$${t}/GostX.app build/Build/Products/$${t}/GostX/ \
	  && ln -s /Applications build/Build/Products/$${t}/GostX/Applications \
	  && hdiutil create build/Build/Products/$${t}/GostX.dmg -ov -volname "GostX" -fs HFS+ -srcfolder build/Build/Products/$${t}/GostX/ \
	  && rm -rf build/Build/Products/$${t}/GostX/

go/libgost.a:
	cd go && $(MAKE)

.PHONY: libgost-clean
libgost-clean:
	cd go && $(MAKE) clean

.PHONY: clean
clean: libgost-clean
	xcodebuild clean -project macos/GostX.xcodeproj -scheme GostX
