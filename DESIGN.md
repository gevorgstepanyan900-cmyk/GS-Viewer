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

## Background playback, lock screen and long videos (2.1)
- `PlaybackService`: MediaSession + media notification (Previous | Play/Pause | Next | Close). The lock screen shows the
  title, picture, previous/next and a seek bar; Bluetooth/headset buttons work.
- Previous/next: inside a playlist = the neighbour in the playlist; otherwise the videos opened in this session
  (like browser history). The same buttons are in the mini (PiP) window.
- Wake lock and Wi-Fi lock are renewed every 5 minutes while playing (no fixed multi-hour limit): 24-30 hour videos play on.
- If Android ends the WebView process (low memory), a new WebView is made and the video continues from the same position.
- Page timers slow down to once per second while the app plays in the background.

## Repeat, previous/next and the lock screen (2.2)
- Repeat is airtight: while it is on, YouTube never sees the "ended" event (so it cannot start its own next video);
  if it moves on anyway, the app opens the looped video again (unless the user / the app chose another one).
- Previous/next work for every video: playlist neighbour, else earlier/later video of the session, else a video from the
  history (previous) / YouTube's own suggestion (next). Buttons are in the app dock, the mini window, the notification,
  and the lock-screen media card (with a seek bar and -10 s / +10 s).
- A video opened by the app (next / previous / playlist) is started even if YouTube does not start it with the screen off.

## Never stuck, with the screen off (2.3)
- A broken page load (no connection, "page not available") is opened again with growing pauses (3 s ... 60 s) and at once
  when the connection comes back; in a playlist a video that still fails after 3 tries although the internet works is skipped.
- The page reports YouTube errors / media errors / 30 s without data: the video is opened again at the same position.
- A video page is never paused by the app (a paused page cannot start the next video), so playlists go on with the screen off.
- While the app is in the background a video that stopped by itself (call, short audio-focus loss) is started again after ~10 s,
  unless the user paused it or another app / a call is using the audio.
- In a playlist the app, not YouTube, opens the next video (YouTube is not told that the video ended).
- The processor stays awake while a retry is planned.
