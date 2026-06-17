# Android-Only Build: README, Makefile, GitHub Workflow & Release Plan

## Scope

Remove all macOS build support from the project. Keep only Android. Update README, Makefile, GitHub Actions workflows, and document the Android release process.

## Makefile Changes

Remove all macOS targets (`macos`, `debug`, `release`, `debug-dmg`, `release-dmg`) and the `go/libgost.a` rule. Add `android-release` for building signed APK + AAB.

```makefile
.PHONY: all android android-release clean

all: android

android: go/gostlib.aar
	cd android && ./gradlew assembleDebug

android-release: go/gostlib.aar
	cd android && ./gradlew assembleRelease bundleRelease

go/gostlib.aar:
	cd go && $(MAKE) gostlib.aar

.PHONY: clean
clean:
	cd go && $(MAKE) clean
	cd android && ./gradlew clean 2>/dev/null || true
```

## README Changes

- Title and description: remove dual-platform and macOS references, describe as Android-only
- Remove dead screenshot link (2023 webm, no longer accessible)
- Keep Android VPN data flow diagram and DNS flow diagram
- Add build instructions: `make android` (debug), `make android-release` (release APK + AAB)

## Android Signing Config

Add signing config to `android/app/build.gradle.kts` that reads keystore parameters from environment variables:

```kotlin
signingConfigs {
    create("release") {
        storeFile = rootProject.file(System.getenv("KEYSTORE_PATH") ?: "upload-keystore.jks")
        storePassword = System.getenv("KEYSTORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS")
        keyPassword = System.getenv("KEY_PASSWORD")
    }
}
```

Release build type references this config.

## GitHub Workflow

### Remove

Delete `feature.yaml` (no longer needed; it built macOS DMG on every branch push).

### Update release.yaml

Trigger: tag push. Environment: `ubuntu-latest`. Outputs: signed APK + AAB published as GitHub Release artifacts (no automatic Google Play upload).

```yaml
on:
  push:
    tags:
      - '*'

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive

      - uses: actions/setup-go@v5
        with:
          go-version: '1.22'

      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - uses: android-actions/setup-android@v3

      - name: Decode keystore
        run: echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > android/upload-keystore.jks

      - name: Build AAR + APK + AAB
        run: make android-release
        env:
          KEYSTORE_PATH: upload-keystore.jks
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}

      - name: Release
        uses: softprops/action-gh-release@v2
        with:
          generate_release_notes: true
          files: |
            android/app/build/outputs/apk/release/*.apk
            android/app/build/outputs/bundle/release/*.aab
```

### Required GitHub Secrets

| Secret | Content |
|--------|---------|
| `KEYSTORE_BASE64` | `base64 -i upload-keystore.jks` output |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias (e.g., `upload`) |
| `KEY_PASSWORD` | Key password |

## Android Release Plan

### One-Time Setup

1. Generate upload keystore: `keytool -genkey -v -keystore upload-keystore.jks -keyalg RSA -keysize 2048 -validity 365000 -alias upload`
2. Register Google Play Console account ($25 one-time fee)
3. Enable Play App Signing in Play Console → Release → App Signing (upload key mode)
4. Configure GitHub Secrets listed above

### First Release

1. Create app in Play Console (`cn.liukebin.gostx`)
2. Set up store listing: screenshots, icon, privacy policy, content rating
3. Build: `make android-release` or push a tag to trigger CI
4. Upload AAB to Play Console → create internal test / closed track / production
5. Wait for review

### Subsequent Releases

```
development → tag push (e.g., v1.0.1)
  → CI builds AAB + APK
  → GitHub Release publishes artifacts
  → manually download AAB and upload to Play Console
```

### Internal Testing (Pre-Release Validation)

- **Internal testing**: upload AAB to internal track, up to 100 test users, no review needed
- **Closed track**: invite test users by email, requires review but is controlled
- Recommended: validate via internal testing first, then promote to production

### Non-Play Store Distribution

If distributing APK through other stores (F-Droid, direct download), note:
- The upload keystore validity is 365000 days (~1000 years, effectively unlimited)
- Ensure the same signing key is used for all APK releases so users can update without reinstalling
