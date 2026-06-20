#!/bin/bash
# Generate Android adaptive icon foreground from logo.png
# Usage: ./scripts/generate-icons.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC="$PROJECT_ROOT/logo.png"
RES="$PROJECT_ROOT/android/app/src/main/res"

if [ ! -f "$SRC" ]; then
    echo "Error: logo.png not found at $SRC"
    exit 1
fi

# Show ring info from source
echo "Source: $(magick "$SRC" -format '%wx%h' info:)"
magick "$SRC" -threshold 50% -trim -format "Ring: %wx%h → %[fx:w/4]dp (in 108dp canvas)" info:
echo

# Generate for each density
magick "$SRC" -resize 432x432 "$RES/mipmap-xxxhdpi/ic_launcher_img.png"
magick "$SRC" -resize 324x324 "$RES/mipmap-xxhdpi/ic_launcher_img.png"
magick "$SRC" -resize 216x216 "$RES/mipmap-xhdpi/ic_launcher_img.png"
magick "$SRC" -resize 162x162 "$RES/mipmap-hdpi/ic_launcher_img.png"
magick "$SRC" -resize 108x108 "$RES/mipmap-mdpi/ic_launcher_img.png"

echo "Done - all 5 densities updated."
