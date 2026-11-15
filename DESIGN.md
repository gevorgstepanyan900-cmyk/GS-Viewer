# GSViewer 2.0 — "Midnight Cinema"

## Design language
- Deep midnight background (`gs_bg`), layered surfaces (`gs_surface`, `gs_surface_high`), one accent gradient
  (violet `#7B61FF` -> pink `#FF5FA2`) used only for the primary action and the active state.
- Everything is rounded (cards 18dp, pills 100dp, sheets 28dp), vector icons, ripples, soft haptics.
- All dialogs are bottom sheets (`Sheets.kt`) — one look everywhere.
- Strings: Armenian (default) + English (`values-en`).

## Where things are
| File | Role |
|---|---|
| `MainActivity.kt` | YouTube player engine (PiP, keeper, ads, history, screen-off) — UI is delegated to `PlayerOverlay` |
| `PlayerOverlay.kt` | Floating dock + mini-window chip + message pill; auto-hides while playing |
| `PlaybackService.kt` | Foreground service for screen-off playback (media notification) |
| `LibraryActivity.kt` | Library: Playlists (cards) / History (grouped by day) |
| `VideosActivity.kt` | One playlist: Play all, rename, delete, remove videos |
| `Sheets.kt`, `Flows.kt` | Bottom sheets and the "add to playlist" journey |
| `PlaylistStore.kt`, `HistoryStore.kt` | Data (unchanged storage format — existing playlists and history are kept) |
| `res/values/colors.xml`, `themes.xml` | Design tokens and styles |
