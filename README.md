# CarTube

Personal-use Android app that browses YouTube on the phone and shows **video-only** playback on **Android Auto** head units (including your 2025 Chery Tiggo 7 PHEV with wired/wireless AA).

> **Safety:** Use only while **parked**. Watching video while driving is dangerous and may be illegal. This app is **not** on Google Play and is intended for sideloading on your own devices.

## How it works

| Surface | Behavior |
|---------|----------|
| **Phone** | In-app `WebView` loads `https://m.youtube.com` so you can search, sign in, and pick videos like the mobile site. |
| **Android Auto** | Registers as a **navigation** car app (community pattern used by CarStream, Fermata Auto, Bolt Player). The head unit receives a `NavigationTemplate` with `ACCESS_SURFACE`; **ExoPlayer** draws decoded video frames onto that surface. Audio routes through the car as normal media. |

**Data path**

1. You open or play a video on the phone (watch URL detected automatically, or tap **Send to car**).
2. WebView cookies sync into **NewPipe Extractor** for age-restricted / signed-in streams when possible.
3. NewPipe resolves an HLS/DASH/progressive URL; ExoPlayer (cap ~480p for Widevine / non-secure surface compatibility) plays on the car `Surface`.
4. Car UI shows play/pause and ±10s seek on touch-enabled head units.

This is **not** an official YouTube client. YouTube may change extraction or DRM at any time.

## Build

**Requirements:** JDK 17+, Android SDK 34.

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`  
A copy is also kept at `artifacts/cartube-debug.apk` after local release builds.

CI: `.github/workflows/build.yml` runs `assembleDebug` and unit tests on push.

## Install on phone

1. Enable **Install unknown apps** for your file manager / browser.
2. Transfer and open `app-debug.apk` (or download from GitHub Actions **cartube-debug-apk** artifact).
3. Open **CarTube** — you should see mobile YouTube in the WebView.

## Android Auto developer setup (required for sideloaded apps)

Sideloaded car apps are **hidden** until developer mode is on.

### 1) Android Auto developer mode

1. Install/update **Android Auto** from Play Store.
2. Open Android Auto → **Settings**.
3. Tap **Version** (or version & build number) **10 times** → “Developer mode enabled”.
4. Open **Developer settings**.
5. Turn on **Unknown sources** (wording varies by AA version).

Keep this on after AA updates, or CarTube may disappear from the car launcher.

**Why Unknown sources alone often fails for Car App Library apps:** On many Android Auto versions (17+), the host validator whitelists **media** / **navigation** sideloads but **not** `template` apps. CarTube therefore registers a **MediaBrowserService** (`automotive_app_desc` includes `<uses name="media" />`) so the package is accepted when sideloaded. Video still uses the **navigation** `CarAppService` + surface.

### 1b) Customize launcher (phone)

1. With the car disconnected or connected, open **Android Auto** on the phone → **Settings**.
2. Open **Customize launcher** (sometimes under **Connection preferences**).
3. Ensure **CarTube** is **checked** for the main screen. On some builds it appears under **Media** first; you can still pin it to the launcher grid.

If CarTube is missing from this list after reinstalling the APK, reboot the phone and open Android Auto once before reconnecting USB/wireless AA.

### 2) Connect the car

- **Wired USB:** use a data-capable cable; accept Android Auto on the phone.
- **Wireless:** follow your Chery / phone OEM steps (Bluetooth + Wi‑Fi).

### 3) Launch on the head unit

1. Open the Android Auto app list on the Chery display.
2. Select **CarTube** (launcher or **Media** section; navigation icon when using the car UI).
3. On the phone, open a YouTube video — playback should start on the car after a short “Loading” period.

If video is black but audio works, reopen CarTube on the head unit so the surface is recreated.

## Approach & policy notes

- **Play Store / official templates:** Google’s public car templates restrict video while driving; this project uses the same **navigation + surface** pattern as community sideload apps, not a Play-distributed video template.
- **Why not mirror the WebView?** Mirroring (`MediaProjection` + `VirtualDisplay`, as in Fermata) needs extra permissions and can be brittle. **Surface + ExoPlayer** is the more common pattern for AA on a phone host today.
- **minSdk 26 / targetSdk 34:** as requested.

## Verified in this repo

| Check | Result |
|-------|--------|
| `./gradlew assembleDebug` | **Pass** |
| Unit tests (`YouTubeUrlParserTest`) | **Pass** |
| DHU / physical head unit | **Not run** in cloud CI (no car hardware/emulator here) |

## Known limitations

- Age-restricted, premium, or heavily DRM content may fail without valid YouTube cookies from the WebView sign-in.
- 480p cap on car video reduces DRM/surface issues on many head units; not true “full HD” on all cars.
- Phone WebView and car player are separate pipelines — scrubbing on the site does not always sync to the car player.
- Google may break NewPipe extraction; update the app/dependency when that happens.
- **Not verified** on a real Chery Tiggo 7 PHEV in this environment — expect to test sign-in, wireless AA, and steering-wheel/touch controls yourself.

## Project layout

```
app/src/main/java/com/cartube/ytauto/
  MainActivity.kt          # Phone WebView (m.youtube.com)
  CarTubeCarAppService.kt  # Android Auto entry (NAVIGATION category)
  VideoPlayerScreen.kt     # NavigationTemplate + SurfaceCallback
  YoutubePlayback.kt       # NewPipe + ExoPlayer for car
  PlaybackController.kt    # Shared player + car Surface
```

## License

Personal/educational use. YouTube content is subject to YouTube’s Terms of Service. No affiliation with Google or YouTube.