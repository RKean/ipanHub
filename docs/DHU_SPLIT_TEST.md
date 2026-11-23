# DHU split / navigation focus test

```bash
# Install debug APK, connect DHU
adb install -r ipanhub-v1.3.4.apk
adb logcat -s CarTube CarTubeMedia | grep -E '\[nav\]|\[car\] onSurface'
```

1. Open **ipanHub** in DHU as navigation app.
2. Start playback from the phone.
3. Confirm: `navigationStarted`, `onSurfaceAvailable container=…x… dpi=…`.
4. Open **Google Maps** or another nav app in DHU and start navigation — ipanHub should log `onStopNavigation`.
5. Return to ipanHub, tap **Take map** — `navigationStarted (user-reclaim)` and a new surface size in logs.

Record the **container=WxH** line for split vs fullscreen in your test notes.