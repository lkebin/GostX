# GostX

An Android VPN proxy app built on [gost v3](https://github.com/go-gost/x). The core proxy engine is written in Go and compiled as a native AAR library that the Android UI calls into.

## Features

- All features of original gost (GostX working in sandbox, some features may not work)
- Support [Tor](https://torproject.org) protocol via `-L tor://:9050?Socks5Proxy=127.0.0.1:1080`

## Build

```bash
make android          # Debug APK
make android-release  # Release APK + AAB (requires keystore)
```
