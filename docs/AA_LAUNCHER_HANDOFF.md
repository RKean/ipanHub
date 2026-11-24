# Android Auto launcher (sideload) — media visibility + navigation video

## History (git)

| Version | `automotive_app_desc` | MediaBrowser root | Tiggo behavior |
|---------|----------------------|-------------------|----------------|
| v1.3.3–1.3.5 (`ffeed459`…) | template + navigation + **media** | Browsable row (search prompt) | Icon visible; host often opened **media browse** (user split-screen photo). Video needs nav template session + surface (v1.3.3 surface fixes). |
| v1.3.4 | no media | empty | **Hidden** on sideload |
| v1.3.6–1.3.7 | media / no media experiments | various | Visible **or** hidden; never both on this OEM |

## v1.3.8 approach

Chery Coolwalk sideload: **declare `media`** so the icon stays listed; **hand off** to `CarTubeCarAppService`:

1. **`onGetRoot` from Gearhead** → delayed `CarUiLauncher` (after user taps launcher).
2. **Play on phone** while AA connected → `CarUiLauncher` from `QueuePlayback` + playback service.
3. **Media session Play** / **play from media id** → same handoff + start playback.

`CarUiLauncher` tries (best-effort, 2.5s cooldown):

- In-app `OPEN_CAR_VIDEO` trampoline + `geo:0,0?q=ipanhub` (NF-6 navigate; manifest activity + `CarAppService` NAVIGATE).

There is **no supported API** to force template UI from the phone if Gearhead ignores these intents. `CarContext.startCarApp` only works **on the car session**, not from the phone.

## Limitations

- Host may still flash the media browse UI before switching.
- Competing nav apps (Waze) can keep the map card.
- Unknown sources + template trust still apply on sideload.