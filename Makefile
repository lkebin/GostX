all: debug

debug: libgost
	xcodebuild -project GostX.xcodeproj -target GostX -configuration Debug
	
release: libgost
	xcodebuild -project GostX.xcodeproj -target GostX -configuration Release 

dmg:
	# xcodebuild will create GostX.app under build/Release folder
	rm -rf build/Release/GostX/
	mkdir build/Release/GostX
	cp -r build/Release/GostX.app build/Release/GostX/
	ln -s /Applications build/Release/GostX/Applications
	hdiutil create build/Release/GostX.dmg -ov -volname "GostX" -fs HFS+ -srcfolder build/Release/GostX/
	rm -rf build/Release/GostX/

libgost:
	make -C go

libgost-clean:
	make -C go clean

clean: libgost-clean
	xcodebuild -project GostX.xcodeproj clean
