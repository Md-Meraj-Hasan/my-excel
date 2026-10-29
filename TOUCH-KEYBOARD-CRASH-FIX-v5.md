# NiceExcel touch / keyboard stability fix v5

## What changed
- Added an edit-session generation token. Delayed keyboard-open, keyboard-dismiss, and focus-loss callbacks are ignored when they belong to an older cell editor.
- Prevented stale `showSoftInput` callbacks from reopening the keyboard after the user has already switched cells.
- Made the delayed IME-dismiss commit conditional on the same edit session still being active, so a Back/keyboard animation cannot accidentally commit a newly selected cell.
- Added Android 13+ system/predictive Back handling: Back commits and closes the active cell editor; when no cell is being edited, Back exits normally.
- Unregisters the Back callback and invalidates pending edit callbacks when the Activity is destroyed.
- Bumped Android version to 1.6.1 (versionCode 17).

## Crash report finding
The supplied OPPO CPH2325 bugreport records an ANR after the main thread spent time rebuilding the worksheet during `duplicateSelectedRowToBottom` -> `render` -> `createRowView` / `rowLineCount`. The report's compiled stack differs from the current v4 source, so it may be from an earlier installed APK. The current source already caches row line counts; this v5 update additionally hardens stale edit/IME callbacks.

## Validation status
- Source-level checks and ZIP integrity checked locally.
- Android Gradle build and real-device rapid-touch testing were not available in this environment. Build with the included GitHub Actions workflow, then test repeated cell taps, switching cells with the keyboard open, keyboard Back, and system navigation Back.
