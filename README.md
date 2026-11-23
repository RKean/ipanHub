# ipanHub

Personal-use Android app (**ipanHub**, package `com.cartube.ytauto`): **co-driver picks songs on the phone**, **video plays on Android Auto** via NavigationTemplate + ExoPlayer.

> **Safety:** Use only while **parked**.

## v1.3.0 — ipanHub rebrand

- User-facing name **ipanHub**; yellow/dark Material 3 theme; hex-hub logo (adaptive + notification + splash)
- `applicationId` unchanged (`com.cartube.ytauto`) for in-place upgrade / AA allow-list

## v1.2.2 — Single player + switch fixes

- Process-wide **PlaybackEngine** (one ExoPlayer); service restarts do not create new players
- **ResolveCoordinator** dedupes in-flight resolves; prefetch is URL-only (no media download)
- Buffering watchdog disabled for mid-track rebuffers; larger HLS **LoadControl** buffers
- Idempotent car **surface** attach; action logging with `p@hash` on every line
- Menu → **Copy debug logs** (last ~300 lines + share)

## v1.2.1 — Search suggestions + playback

- **YouTube-style autocomplete** under the search bar (NewPipe `SuggestionExtractor`, Google suggest fallback), 250ms debounce, stale request cancel
- **Recent searches** when the field is empty (tap to search, arrow to fill, X to remove)
- Faster track switches, buffering watchdog, HLS-first resolve, loading state on notification/mini-player

## v1.2.0 — Remote + queue

| Surface | Behavior |
|---------|----------|
| **Phone** | Native Material 3 **search** (NewPipe), **queue**, mini-player remote — **no on-screen video** |
| **Car** | Fullscreen video; prev / play-pause / next map to the queue |
| **No AA** | **Audio-only** foreground playback on the phone |

Share a YouTube link into ipanHub to queue/play.

## Build

```bash
./gradlew assembleDebug test
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Play-source install (video on car screen)

See **[docs/VIDEO_ON_CAR.md](docs/VIDEO_ON_CAR.md)**:

```bat
adb install -r -g -i com.android.vending cartube-debug.apk
```

## Architecture

- **QueueController** — persisted queue + play head (disk)
- **CarTubePlaybackService** — single ExoPlayer + MediaSession; auto-advance; prefetch next stream; **StreamCache**
- **PlaybackClient** — phone UI sync
- Hidden **CookieWebView** for YouTube cookies only

## Verified in CI

`assembleDebug` and unit tests (including `QueueControllerTest`) pass.