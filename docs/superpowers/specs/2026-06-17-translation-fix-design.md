# Translation Fix & Optimization

## Problem

`values/strings.xml` (default locale) has Chinese text mixed into Settings, per-app proxy, app filter, and file management sections. `values-zh/strings.xml` is missing ~30 keys. Two existing zh translations are unnatural.

## Changes

### `values/strings.xml` — restore English defaults

Settings | Per-app proxy | File management sections: Chinese → English (concise style matching existing strings). Add missing `nav_settings`.

### `values-zh/strings.xml` — fill missing keys

Add all missing keys: Settings (13), battery optimization (3), per-app proxy (10), file management (17).

### Optimize 2 existing zh translations

- `log_follow_on`: "开启动态载入" → "恢复实时滚动"
- `log_follow_off`: "暂停动态载入" → "暂停实时滚动"

## Key decisions

- Default English style: concise (matches existing "Running", "Connecting...", "No logs" pattern)
- zh translations reuse original Chinese text from default file where applicable
- Battery optimization strings: translated fresh since no prior Chinese existed
