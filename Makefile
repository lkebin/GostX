all: debug

debug: libgost
	xcodebuild -project GostX.xcodeproj -target GostX -configuration Debug
	
release: libgost
	xcodebuild -project GostX.xcodeproj -target GostX -configuration Release 

libgost:
	make -C go

libgost-clean:
	make -C go clean

clean: libgost-clean
	xcodebuild -project GostX.xcodeproj clean
