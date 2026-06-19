# Update App Icons & Splash Screen

## Summary

Replace launcher icon, Quick Settings tile icon, and splash screen branding with new assets (`gost-log.png`, `gost-tile.svg`).

## Design

### Launcher Icon

- Change adaptive icon background from white to black: `ic_launcher_background` → `#000000`
- Replace `ic_launcher_img.png` in all five mipmap densities (mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi) with scaled versions of `gost-log.png`
- Keep existing `ic_launcher_foreground.xml` (centered bitmap) and `ic_launcher.xml` (adaptive icon) unchanged

**Mipmap sizes** (foreground bitmap is 108dp × 108dp at mdpi):

| Density | Size |
|---------|------|
| mdpi    | 108×108px |
| hdpi    | 162×162px |
| xhdpi   | 216×216px |
| xxhdpi  | 324×324px |
| xxxhdpi | 432×432px |

### Tile Icon

- Convert `gost-tile.svg` to white monochrome Android Vector Drawable XML
- Replace `res/drawable/ic_tile_vpn.xml`, keeping 24dp viewport

### Splash Screen

- New `res/drawable/ic_splash_branding.xml`: centered `@mipmap/ic_launcher_img` (same pattern as `ic_launcher_foreground.xml`)
- Add `android:windowSplashScreenAnimatedIcon` to light and dark themes

## Files changed

| File | Action |
|------|--------|
| `res/values/colors.xml` | Edit — background color to black |
| `res/mipmap-{density}/ic_launcher_img.png` (×5) | Replace — scaled `gost-log.png` |
| `res/drawable/ic_tile_vpn.xml` | Rewrite — vector from `gost-tile.svg` |
| `res/drawable/ic_splash_branding.xml` | New |
| `res/values/themes.xml` | Edit — add splash icon |
| `res/values-night/themes.xml` | Edit — add splash icon |

## Verification

- `make android` builds without errors
- Launcher icon: `gost-log.png` on black background
- Quick Settings tile: white gost-tile icon
- App launch: `gost-log.png` on splash screen
