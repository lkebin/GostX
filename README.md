# GostX

A gost client for Android.

<a href="https://play.google.com/store/apps/details?id=cn.liukebin.gostx">
  <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" width="180" />
</a>

## Features

- **TUN** — Stream traffic through a Gost proxy chain via TUN device
- **Per-app proxy** — Choose which apps route through the tunnel and which bypass it
- **YAML configuration** — Define services, chains, hops, and bypasses in a single config file
- **System DNS** — Resolve domains through the proxy tunnel or use system DNS directly
- **Multi-profile** — Manage separate configurations with profile rename, delete, and quick switching

## Build

```bash
make android          # Debug APK
make android-release  # Release APK + AAB (requires keystore)
```
