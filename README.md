# ipanHub

**ipanHub** is a personal Android app for **Android Auto**: a co-driver searches and queues songs on the phone while **video plays on the car head unit** (Android Auto navigation template + video surface). Package id: `com.cartube.ytauto` (kept for in-place upgrades and AA sideload allow-lists).

> **Safety:** Use only while **parked**. You are responsible for complying with local distracted-driving laws.

## Disclaimer

This is a **personal / educational** project. **Not affiliated with, endorsed by, or sponsored by YouTube, Google, or Android Auto.** Stream resolution uses **[NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor)** (GPL-3.0). You must comply with YouTube’s Terms of Service and applicable copyright law. The authors provide no warranty.

## Features

| Surface | What you get |
|---------|----------------|
| **Phone** | Material 3 dark UI (yellow accents), YouTube search (NewPipe), queue, autocomplete, mini-player remote — **no on-phone fullscreen video** |
| **Car** | Fullscreen video on the NavigationTemplate surface; action strip: **previous · play/pause · next** |
| **Playback** | Single process-wide ExoPlayer, queue-driven, HLS-first start, share-link into app |

- YouTube-style search suggestions and recent searches  
- Persistent queue with drag-reorder  
- Play-source install path for template (video) on many AA builds (see below)

## Screenshots

> Replace these placeholders with your own captures after install.

| Phone (search / queue) | Phone (mini-player) | Car (DHU / head unit) |
|------------------------|---------------------|----------------------|
| `docs/screenshots/phone-search.png` *(add)* | `docs/screenshots/phone-mini-player.png` *(add)* | `docs/screenshots/car-video.png` *(add)* |

## Build

Requirements: **JDK 17**, Android SDK **API 34**.

```bash
./gradlew assembleDebug test
```

Debug APK (not committed to git):

`app/build/outputs/apk/debug/app-debug.apk`

## Install (debug, video on car screen)

Many Android Auto builds only allow **template / video surface** apps when installed with **Google Play as the installer package**. See **[docs/VIDEO_ON_CAR.md](docs/VIDEO_ON_CAR.md)** for full steps.

```bash
adb install -r -g -i com.android.vending app-debug.apk
```

On the phone: **Android Auto → Developer settings → Unknown sources** (or “Add extra app”) enabled, then add **ipanHub** under apps/navigation (not Media-only).

## Architecture (short)

- **QueueController** — persisted queue + play head  
- **PlaybackEngine** + **CarTubePlaybackService** — one ExoPlayer, MediaSession, NewPipe resolve + cache  
- **CarTubeCarAppService** — car UI (video surface + controls)  
- Hidden **CookieWebView** for optional YouTube cookies (no credentials in repo)

## License

**GNU General Public License v3.0** — see [LICENSE](LICENSE). GPLv3 applies because this project depends on NewPipe Extractor (GPL-3.0).