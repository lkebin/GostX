all: debug

debug:
	xcodebuild -project GostX.xcodeproj -target GostX -configuration Debug
	
release: 
	xcodebuild -project GostX.xcodeproj -target GostX -configuration Release 

clean:
	xcodebuild -project GostX.xcodeproj clean
