Placeholder for gomobile output.

Attempted command:
  cd go && ANDROID_HOME=/Users/kbliu/Workspace/sdk/android GOFLAGS=-buildvcs=false gomobile bind \
    -target android/arm,android/arm64,android/amd64 -androidapi 26 -trimpath -ldflags="-s -w" \
    -o gostlib.aar ./gostlib

Current blocker:
  gomobile bind requires golang.org/x/mobile in the current module dependency graph.

To build manually once gomobile tooling is configured:
  export PATH="$PATH:/Users/kbliu/.go/bin"
  export ANDROID_HOME=/Users/kbliu/Workspace/sdk/android
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  cd /Users/kbliu/Workspace/project/GostX/.worktrees/feature/android-client-gost-v3/go
  gomobile init
  GOFLAGS="-buildvcs=false" gomobile bind \
    -target android/arm,android/arm64,android/amd64 \
    -androidapi 26 \
    -trimpath -ldflags="-s -w" \
    -o gostlib.aar \
    ./gostlib
  cp gostlib.aar ../android/app/libs/gostlib.aar
