# CarTube

Personal-use Android app that browses YouTube on the phone and shows **video-only** playback on **Android Auto** head units (including your 2025 Chery Tiggo 7 PHEV with wired/wireless AA).

> **Safety:** Use only while **parked**. Watching video while driving is dangerous and may be illegal. This app is **not** on Google Play and is intended for sideloading on your own devices.

## How it works

| Surface | Behavior |
|---------|----------|
| **Phone** | In-app `WebView` loads `https://m.youtube.com` so you can search, sign in, and pick videos like the mobile site. |
| **Android Auto (video)** | `CarTubeCarAppService` + **NavigationTemplate** + **surface** → ExoPlayer draws video on the head unit (requires **Play-source install** — see below). |
| **Android Auto (audio fallback)** | If installed normally, only **MediaBrowserService** passes AA validation → **audio + thumbnail** on the media UI. |

**Important:** A normal APK install shows **audio only** on the car. For **video on screen**, follow **[docs/VIDEO_ON_CAR.md](docs/VIDEO_ON_CAR.md)** (Windows `adb install -i com.android.vending …` or Shizuku + UnknownAuto).

## Build

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Quick install (phone only — audio on car, no head-unit video)

1. Install the APK from a file manager.
2. Android Auto → Developer mode → **Unknown sources** ON.
3. **Customize launcher** → enable CarTube (often under **Media**).

## Video on the Chery display (required steps)

See **[docs/VIDEO_ON_CAR.md](docs/VIDEO_ON_CAR.md)**. Short version:

```bat
adb uninstall com.cartube.ytauto
adb install -r -g -i com.android.vending cartube-debug.apk
```

Then open **CarTube from the AA app list (navigation)**, not only the Media entry.

The phone app shows an orange/green **install banner** and an in-app guide (tap the banner).

## Android Auto developer setup

1. Android Auto → Settings → tap **Version** 10× → **Developer settings** → **Unknown sources** ON.
2. **Customize launcher** → check CarTube.
3. Reboot phone + reconnect USB/wireless AA if the app list is stale.

## Phone background playback (v1.0.4)

When you play a video in the WebView and **leave the app or lock the screen**, CarTube **hands off** to the foreground player at the current position (like Premium background play). Use the media notification or Bluetooth headset buttons.

**Tips if audio stops on your OEM phone:** Settings → Apps → CarTube → Battery → **Unrestricted** (disable aggressive optimization).

## Features (v1.0.4)

- NewPipe **v0.26.5** stream resolution + InnerTube fallback
- Foreground **MediaSessionService** playback (survives phone home/screen off)
- WebView silenced when sending to car
- Media-browser registration for visibility when sideloaded
- Play-source install guide + artwork on media UI when video template unavailable

## Verified in CI

| Check | Result |
|-------|--------|
| `assembleDebug` / unit tests | Pass |
| Real Chery Tiggo 7 PHEV | User-tested: media UI works; video needs Play-source install |

## License

Personal/educational use. YouTube content is subject to YouTube’s Terms of Service.