# Android UI M3 Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align HomeScreen, LogScreen, and ConfigScreen with Material Design 3 — proper color tokens, shapes, spacing, touch targets, and component variants.

**Architecture:** Pure Compose UI changes in 3 screen files. No ViewModel, data layer, or navigation changes. Each screen is independent so tasks can be done in order.

**Tech Stack:** Jetpack Compose, Material3 (BOM 2024.02.00), Kotlin

**Spec:** `docs/superpowers/specs/2026-06-06-android-ui-m3-optimization-design.md`

---

### Task 1: HomeScreen — FAB to LargeFloatingActionButton, surface colors, hardcoded color removal

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/gostx/ui/home/HomeScreen.kt`

- [ ] **Step 1: Replace FAB with LargeFloatingActionButton and fix hardcoded FAB color**

  Remove `import androidx.compose.foundation.shape.CircleShape` (no longer needed).

  Change the `floatingActionButton` composable from:
  ```kotlin
  floatingActionButton = {
      FloatingActionButton(
          onClick = {
              if (!isTransitioning) vm.toggleVpn(onRequestVpnPermission)
          },
          shape = CircleShape,
          containerColor = when (vpnState.status) {
              VpnStatus.CONNECTED -> Color(0xFF4CAF50)
              VpnStatus.ERROR -> MaterialTheme.colorScheme.error
              else -> MaterialTheme.colorScheme.primaryContainer
          }
      ) {
  ```
  to:
  ```kotlin
  floatingActionButton = {
      LargeFloatingActionButton(
          onClick = {
              if (!isTransitioning) vm.toggleVpn(onRequestVpnPermission)
          },
          containerColor = when (vpnState.status) {
              VpnStatus.CONNECTED -> MaterialTheme.colorScheme.primary
              VpnStatus.ERROR -> MaterialTheme.colorScheme.error
              else -> MaterialTheme.colorScheme.primaryContainer
          }
      ) {
  ```

  Remove `import androidx.compose.ui.graphics.Color` if it's no longer used elsewhere (check — `Color.Transparent` is used for the progress indicator track, so keep the import).

- [ ] **Step 2: Fix profile list surface container**

  Change the `Surface` wrapping the `LazyColumn` from:
  ```kotlin
  Surface(
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surface,
      shadowElevation = 1.dp,
      modifier = Modifier.fillMaxWidth()
  ) {
  ```
  to:
  ```kotlin
  Surface(
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      modifier = Modifier.fillMaxWidth()
  ) {
  ```

  Remove `import androidx.compose.foundation.shape.RoundedCornerShape` if it's no longer used elsewhere (check — it's used in `BatteryOptimizationBanner` with `RoundedCornerShape(12.dp)`, so keep the import).

- [ ] **Step 3: Verify build**

  Run: `cd android && ./gradlew assembleDebug`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

  ```bash
  git add android/app/src/main/kotlin/cn/liukebin/gostx/ui/home/HomeScreen.kt
  git commit -m "ui: M3 optimize HomeScreen — LargeFAB, surfaceContainerHigh, remove hardcoded color"
  ```

---

### Task 2: LogScreen — SelectionContainer, spacing, empty state polish

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/gostx/ui/log/LogScreen.kt`

- [ ] **Step 1: Add SelectionContainer wrapper to log lines**

  Add import:
  ```kotlin
  import androidx.compose.foundation.text.selection.SelectionContainer
  ```

  Change the `items` block in `LazyColumn` from:
  ```kotlin
  items(lines) { line ->
      Text(
          line,
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
          modifier = Modifier
              .fillMaxSize()
              .padding(vertical = 1.dp)
      )
  }
  ```
  to:
  ```kotlin
  items(lines) { line ->
      SelectionContainer {
          Text(
              line,
              fontFamily = FontFamily.Monospace,
              fontSize = 13.sp,
              modifier = Modifier
                  .fillMaxSize()
          )
      }
  }
  ```

- [ ] **Step 2: Fix LazyColumn spacing and padding**

  Add import:
  ```kotlin
  import androidx.compose.foundation.layout.Arrangement
  ```

  Change the `LazyColumn` modifier from:
  ```kotlin
  LazyColumn(
      state = listState,
      modifier = Modifier
          .fillMaxSize()
          .padding(padding)
          .padding(horizontal = 12.dp),
      contentPadding = PaddingValues(vertical = 8.dp)
  ) {
  ```
  to:
  ```kotlin
  LazyColumn(
      state = listState,
      modifier = Modifier
          .fillMaxSize()
          .padding(padding)
          .padding(horizontal = 16.dp),
      contentPadding = PaddingValues(vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp)
  ) {
  ```

- [ ] **Step 3: Improve empty state**

  Add import:
  ```kotlin
  import androidx.compose.foundation.background
  ```

  Change the empty state `Box` from:
  ```kotlin
  if (lines.isEmpty()) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
          Text(
              stringResource(R.string.log_empty),
              color = MaterialTheme.colorScheme.onSurfaceVariant
          )
      }
  }
  ```
  to:
  ```kotlin
  if (lines.isEmpty()) {
      Box(
          modifier = Modifier
              .fillMaxSize()
              .padding(padding)
              .background(MaterialTheme.colorScheme.surfaceContainerHigh),
          contentAlignment = Alignment.Center
      ) {
          Text(
              stringResource(R.string.log_empty),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant
          )
      }
  }
  ```

- [ ] **Step 4: Remove unused import**

  Remove `import androidx.compose.foundation.layout.PaddingValues` — it's no longer needed since `contentPadding` is gone.

- [ ] **Step 5: Verify build**

  Run: `cd android && ./gradlew assembleDebug`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

  ```bash
  git add android/app/src/main/kotlin/cn/liukebin/gostx/ui/log/LogScreen.kt
  git commit -m "ui: M3 optimize LogScreen — selection support, spacing, empty state"
  ```

---

### Task 3: ConfigScreen — minLines, animated save indicator

**Files:**
- Modify: `android/app/src/main/kotlin/cn/liukebin/gostx/ui/config/ConfigScreen.kt`

- [ ] **Step 1: Add minLines to OutlinedTextField**

  Add import:
  ```kotlin
  import androidx.compose.animation.AnimatedVisibility
  import androidx.compose.animation.fadeIn
  import androidx.compose.animation.fadeOut
  ```

  Change the YAML `OutlinedTextField` to add `minLines`:
  ```kotlin
  OutlinedTextField(
      value = state.yaml,
      onValueChange = { vm.onYamlChange(it) },
      modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
      textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
      minLines = 10,
  )
  ```

- [ ] **Step 2: Add animated save indicator**

  We need a `remember` + `LaunchedEffect` to auto-hide the saved text. Add a state variable after the existing state declarations (around line 104):

  ```kotlin
  var showSaved by remember { mutableStateOf(false) }
  ```

  Add a `LaunchedEffect` keyed on `state.isSaved` to auto-hide after 2 seconds (after the existing `LaunchedEffect` blocks, around line 114):

  ```kotlin
  LaunchedEffect(state.isSaved) {
      if (state.isSaved) {
          showSaved = true
          kotlinx.coroutines.delay(2000)
          showSaved = false
      }
  }
  ```

  Wrap the save indicator in `AnimatedVisibility`. Replace:
  ```kotlin
  if (state.isSaved) {
      Spacer(Modifier.height(4.dp))
      Text(
          stringResource(R.string.config_saved),
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.bodySmall
      )
  }
  ```
  with:
  ```kotlin
  AnimatedVisibility(
      visible = showSaved,
      enter = fadeIn(),
      exit = fadeOut()
  ) {
      Column {
          Spacer(Modifier.height(4.dp))
          Text(
              stringResource(R.string.config_saved),
              color = MaterialTheme.colorScheme.primary,
              style = MaterialTheme.typography.bodySmall
          )
      }
  }
  ```

- [ ] **Step 3: Verify build**

  Run: `cd android && ./gradlew assembleDebug`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

  ```bash
  git add android/app/src/main/kotlin/cn/liukebin/gostx/ui/config/ConfigScreen.kt
  git commit -m "ui: M3 optimize ConfigScreen — minLines, animated save indicator"
  ```

---

### Verification

After all tasks complete, verify the build compiles cleanly:

```bash
cd android && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL in 0s (incremental, all tasks up-to-date)
