# File Import Design

## Summary

Add a file management page where users can import files (bypass rule lists, TLS
certificates, etc.) into the app's gost working directory via the system
SAF file picker, then reference them in gost YAML configs by relative path.

## Motivation

GostX sets the gost runtime working directory to `getExternalFilesDir(null)`,
so users can reference local files in their YAML configs (e.g.,
`bypass.file.path: china_ip_list.txt`, `tls.caFile: ca.crt`). Currently there
is no way for users to get files into that directory from the app.

## Architecture

```
SettingsScreen
  └─ "文件管理" entry → FileManageScreen

FileManageScreen
  ├── File list (name, size, lastModified)
  ├── [Import] FAB → SAF ACTION_OPEN_DOCUMENT → copy to workDir
  ├── Per-file overflow menu: Rename / Delete / Copy path
  └── Tap file row → copy relative path to clipboard + Snackbar

data/FileRepository.kt
  └── List, import, rename, delete files in workDir
```

No new permissions needed — SAF grants temporary read access to the
user-selected file.

## Components

### FileRepository

```
class FileRepository(context: Context) {
    val workDir: File

    fun listFiles(): List<FileInfo>        // non-hidden files only
    suspend fun importFile(uri: Uri, cr: ContentResolver): Result<FileInfo>
    fun renameFile(oldName: String, newName: String): Result<Unit>
    fun deleteFile(name: String): Result<Unit>
}

data class FileInfo(name: String, sizeBytes: Long, lastModified: Long)
```

- `workDir`: `getExternalFilesDir(null)` with `filesDir` fallback
- `listFiles`: returns flat list, skips directories and dotfiles
- `importFile`: reads source via `ContentResolver.openInputStream(uri)`,
  writes to `workDir/<originalFileName>`; caller handles overwrite confirmation
- `renameFile`: validates target name (no `/`, no duplicate)
- `deleteFile`: `File.delete()`

### FileManageViewModel

```
class FileManageViewModel(application: Application) : AndroidViewModel(app) {
    val files: StateFlow<List<FileInfo>>
    val toast: SharedFlow<String>

    fun refresh()
    fun importFile(uri: Uri)
    fun renameFile(oldName: String, newName: String)
    fun deleteFile(name: String)
    fun copyPath(name: String)  // clipboard + toast
}
```

### FileManageScreen

- Top bar: "文件管理" title + back arrow
- Empty state: centered hint "暂无文件，点击右下角导入"
- File list: `LazyColumn` with rows showing name, formatted size, date
- Each row overflow: `⋮` → DropdownMenu (Rename / Delete / Copy path)
- FAB: "+" icon → `ACTION_OPEN_DOCUMENT`
- Rename dialog: `AlertDialog` + `TextField`, validates no duplicate/empty/illegal chars
- Delete dialog: confirmation `AlertDialog`
- Tap row: copy relative path → Snackbar "已复制到剪贴板"

### Navigation

- Add `FileManage` to the sealed navigation class
- SettingsScreen: add "文件管理" row with chevron, navigate on tap

## Error Handling

| Scenario | Behavior |
|---|---|
| workDir missing | Create directory; if fail, block all ops with toast |
| SAF uri not found | Toast "导入失败：找不到文件" |
| Same-name overwrite | AlertDialog confirmation before overwriting |
| Rename conflict | Toast "文件名已存在" |
| Rename illegal chars | Real-time validation, confirm button disabled |
| Import read/write error | Toast with specific error message |

## Out of Scope

- Receiver for `ACTION_SEND` (share-to-app) — may be added later
- Directory support, nested browsing
- Image/mime-type filtering in SAF picker
