# Video on the car screen (Android Auto)

## Root cause

Android Auto’s host validator treats **Car App Library** apps (`<uses name="template"/>`) differently from **media** apps.

| Install recorded as | What appears on the head unit |
|---------------------|--------------------------------|
| File manager, `adb install` without `-i`, browser | **Media player only** (audio + browse UI). `CarTubeMediaBrowserService` passes validation. |
| **Google Play** (`com.android.vending`) | **Navigation / template app** + surface for video. `CarTubeCarAppService` + `NavigationTemplate` can run. |

**Unknown sources** in Android Auto developer settings does **not** whitelist `template` on current AA versions (see [Lapin #1](https://github.com/prototux/lapin/issues/1)). CarTube added the media service so the app is visible at all when sideloaded; that is why you hear audio but see no video.

Community video apps (CarStream, Fermata, etc.) rely on the same pattern: **Play-source attribution at install time**, plus navigation/projection services — not a normal APK tap.

**Rooting is not required** on most phones (Android 10–14). Android 15–17 may need **Shizuku** tools ([UnknownAuto](https://github.com/GitMag/UnknownAuto)) if plain ADB `-i` fails.

## Recommended install (Windows PC + USB debugging)

1. **Uninstall** any existing CarTube from the phone.
2. Enable **Developer options** → **USB debugging** on the phone. Connect USB, accept the RSA prompt.
3. Install [platform-tools](https://developer.android.com/tools/releases/platform-tools) (includes `adb`).
4. Download `cartube-debug.apk` to the PC (same folder you open in Command Prompt).

```bat
adb devices
adb uninstall com.cartube.ytauto
adb install -r -g -i com.android.vending cartube-debug.apk
```

Alternate (push + shell install — some OEMs prefer this):

```bat
adb push cartube-debug.apk /data/local/tmp/cartube.apk
adb shell pm install -i com.android.vending -r -g /data/local/tmp/cartube.apk
```

5. **Verify** installer package:

```bat
adb shell dumpsys package com.cartube.ytauto | findstr installer
```

You want `installerPackageName=com.android.vending` (wording varies by Android version).

6. Android Auto: **Developer mode** → **Unknown sources** ON (still recommended).
7. **Customize launcher** — enable **CarTube**. After Play-source install you should see the **app/navigation** entry (not only under Media). Open **that** entry for video.
8. On the phone: pick a video → **Send to car**.

## Phone-only install (no PC)

Use a companion that sets the Play install source via the system installer:

- [UnknownAuto](https://github.com/GitMag/UnknownAuto/releases) + [Shizuku](https://shizuku.rikka.app/) (open source, same technique as AA Enabler).
- [AAAD](https://github.com/shmykelsa/AAAD) for apps it hosts; for your own APK use UnknownAuto or ADB.

Wireless debugging pairing is required once for Shizuku (no PC after setup).

## Last resort: media UI only

If template access cannot be obtained on your AA version, CarTube still plays **audio** in the media UI and pushes the YouTube **thumbnail** as session artwork (static image, not video). There is no supported way to draw full video on the head unit without the template surface.

## Confidence

| Scenario | Likelihood |
|----------|------------|
| `adb install -i com.android.vending` on Android 12–14 + AA 10–13 | **High** (widely reported for aa-torque, AAAD, UnknownAuto docs) |
| Chery Tiggo 7 2025 + your phone exact AA build | **Medium** — not lab-tested here |
| Android Auto **17+** / Android **16** | **Medium–low** — extra Shizuku/UnknownAuto retries may be needed |
| Normal sideload only | **Won’t show video** — media/audio only (confirmed on your car) |