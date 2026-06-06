# Android UI Material Design 3 Optimization

## Overview

Optimize the GostX Android app UI across three screens (Home, Log, Config) to fully align with Material Design 3 in Jetpack Compose. The app is a VPN client that uses dynamic color (Android 12+) and already has a Material3 foundation but needs refinement in component usage, color token discipline, spacing, and interactive area sizing.

## Scope

Three screens only — no layout restructuring, no new screens, no navigation changes:

- **HomeScreen**: Profile list + VPN status + VPN toggle
- **LogScreen**: Monospace log viewer with live tail
- **ConfigScreen**: YAML editor

## 1. Global M3 Token Alignment

### Color
- Remove all hardcoded `Color(0x...)` values. Replace with `MaterialTheme.colorScheme` roles.
- Specifically: VPN FAB green (`Color(0xFF4CAF50)`) → `MaterialTheme.colorScheme.primary` (when connected) / `primaryContainer` (stopped) / `error` (error state). The transitioning (connecting/stopping) state already uses `primaryContainer` — this is correct and stays.
- Use `surfaceContainerHigh` / `surfaceContainerHighest` for list container areas instead of `Surface` with `shadowElevation` (which is an MD2 pattern and has no visual effect in M3 without tonal overlay).

### Shape
- Hardcoded `RoundedCornerShape(16.dp)`, `RoundedCornerShape(12.dp)` → `MaterialTheme.shapes.medium`, `MaterialTheme.shapes.large` where semantically appropriate.

### Elevation in M3
- MD3 communicates elevation through tonal surface color (e.g., `surfaceContainer` variants), not shadows.
- Remove `shadowElevation` from Surface composables in the profile list. Use `surfaceContainerHigh` color instead.

### Touch Targets
- Ensure all `IconButton` and clickable areas meet 48dp minimum touch target. `IconButton` already does this by default, but explicit `Modifier.size(48.dp)` on custom clickable areas.

## 2. HomeScreen

### VPN Toggle FAB
- Change `FloatingActionButton` to `LargeFloatingActionButton` (M3 large variant).
- Container color:
  - `VpnStatus.CONNECTED` → `MaterialTheme.colorScheme.primary`
  - `VpnStatus.ERROR` → `MaterialTheme.colorScheme.error` (unchanged, correct)
  - `VpnStatus.STOPPED` → `MaterialTheme.colorScheme.primaryContainer` (was correct)
  - Transitioning → `MaterialTheme.colorScheme.primaryContainer` (correct) + `CircularProgressIndicator` stays
- The FAB already uses `FabPosition.Center` — keep this.
- Icon stays `painterResource(R.drawable.ic_tile_vpn)`.
- Ensure the progress indicator uses `MaterialTheme.colorScheme.onPrimaryContainer` (already correct).

### Profile List
- Keep current layout: `LazyColumn` inside a container surface.
- Replace `Surface` + `shadowElevation(1.dp)` with `Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh)`.
- `RoundedCornerShape(16.dp)` → `MaterialTheme.shapes.large`.
- ProfileListItem:
  - Keep `ListItem` composable with `RadioButton` leading, name headline, `IconButton` trailing.
  - Trailing `IconButton`: ensure minimum touch target is 48dp (default behavior, but confirm).
  - `HorizontalDivider` inset: `Modifier.padding(start = 56.dp)` already correct.

### Battery Optimization Banner
- No changes — already uses `tertiaryContainer` / `onTertiaryContainer` and `RoundedCornerShape(12.dp)`.

## 3. LogScreen

### Text Selection Support
- Wrap each log line `Text` in a `SelectionContainer` to allow long-press selection for copy.
- Keep the existing "Copy All" (`ContentCopy`) icon button that copies full file contents via `ClipboardManager`.
- Performance note: `SelectionContainer` has overhead. Only the visible log lines are composed, so wrapping each line in its own `SelectionContainer` is acceptable. Monitor if scrolling performance degrades with 2000+ lines.

### Spacing & Layout Polish
- Increase horizontal padding from `12.dp` to `16.dp` for consistency with screens.
- Use `verticalArrangement = Arrangement.spacedBy(2.dp)` in the `LazyColumn` for uniform line spacing (currently uses `padding(vertical = 1.dp)` per item).
- Log text style: keep `FontFamily.Monospace`, increase `fontSize` from `12.sp` to `13.sp` for readability.

### Empty State
- Keep the centered "No logs" text but add `surfaceContainerHigh` background to the empty `Box` area for visual distinction.
- Use `bodyLarge` style instead of default for empty state text.

### TopAppBar
- Icon buttons stay the same (back, play/pause, copy, clear).
- Ensure consistent icon color: `MaterialTheme.colorScheme.onSurface` (default behavior).

## 4. ConfigScreen

### YAML Editor
- OutlinedTextField: add `minLines = 10` so the editor is not collapsed on first load.
- Keep `TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)` — good for code editing.
- TextField colors: ensure `focusedBorderColor`, `unfocusedBorderColor`, `cursorColor` use `MaterialTheme.colorScheme` roles (OutlinedTextField defaults to this).

### Save Status Indicator
- "Saved" indicator currently uses `Text` with `MaterialTheme.colorScheme.primary` — this is correct.
- Keep placement below the text field, but add slight animation: use `AnimatedVisibility` to fade in/out the "Saved" text (shows after save, auto-hides after 2 seconds of no edits).

### Dialogs (Rename, Delete, Validation Error)
- All already use `AlertDialog` — no structural changes needed.
- Ensure dialog buttons use `TextButton` (current) — correct for M3.

### TopAppBar
- Actions stay: Save, Rename, Delete.
- Delete button uses `enabled = state.canDelete` — this is correct. Disabled state uses M3 default low-opacity styling.
- Keep `Icons.Filled.Save`, `Icons.Filled.Edit`, `Icons.Filled.Delete`.

## Out of Scope

- Navigation patterns (no Bottom Nav, no Navigation Rail)
- SettingsScreen, AppFilterScreen — not included in this optimization
- Theme/material design system setup (dynamic color already implemented)
- Adding new features (syntax highlighting, search/filter, traffic stats)

## Implementation Order

1. Global M3 token alignment (color, shape, elevation)
2. HomeScreen: FAB → LargeFAB, surface colors, touch target review
3. LogScreen: selection support, spacing, empty state
4. ConfigScreen: editor min lines, save indicator animation
