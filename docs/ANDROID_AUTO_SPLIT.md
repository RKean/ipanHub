# Android Auto split screen / Coolwalk / Waze

## Root cause (Tiggo 7 photo)

The head unit showed **“Search and tap a result to play on the car”** — that row came from the **legacy MediaBrowser** path, not the Car App **NavigationTemplate** surface. With **Waze** as active nav, Coolwalk kept Waze on the **map card** and ipanHub on a **side list card** with **no `ACCESS_SURFACE`**.

v1.3.4+ changes entry to **navigation only**. **v1.3.5** never paints on the car `Surface` with Canvas (ExoPlayer needs exclusive surface access). (`automotive_app_desc`: template + navigation, no media browse UI) and always opens **`VideoPlayerScreen`** = `NavigationTemplate` + `SurfaceCallback`.

## Claiming the map card

ipanHub calls:

- `NavigationManager.navigationStarted()` when the car screen is visible and when playback starts in the car
- `NavigationManager.updateTrip()` (and a 4s pulse while active)
- `navigationEnded()` when playback stops or the car session ends

**Can ipanHub keep the map while Waze relaunches?**

**No — not reliably.** Android Auto allows **one active navigation app**. The host (Gearhead / head unit) arbitrates. If **Waze has an active route**, it can reclaim focus and the host calls **`onStopNavigation`** on ipanHub. Google’s navigation quality rules (NF-5) require ipanHub to **stop** acting as the active nav app when that happens — we cannot “fight” Waze in the background.

### Most reliable workaround for users

1. **End/cancel the Waze route** (Stop navigation), not only swipe Waze away — Waze often restarts the route and reclaims the map.
2. Open **ipanHub from Navigation / map apps** in the AA launcher (not the old media browse list).
3. On ipanHub, tap **Take map** if video is missing after Waze stole focus.
4. Optional: **disable Waze in Android Auto** (phone: Android Auto → customize launcher) while using ipanHub video, or use Waze only on the phone screen.
5. Start playback **after** ipanHub owns the map, or tap **Take map** again after starting.

## Debugging

Phone menu → **Copy debug logs** → filter `[nav]` and `[car] onSurfaceAvailable`.

Expected when successful: `[nav] navigationStarted` then `[car] onSurfaceAvailable container=WxH`.

## DHU smoke test

1. Desktop Head Unit, Coolwalk/multi-pane enabled.
2. Start a fake “second nav” session if your DHU build supports it, or resize to split.
3. Launch ipanHub car app; confirm logs show surface dimensions (e.g. `800x480` split vs full width).

See also `docs/DHU_SPLIT_TEST.md`.