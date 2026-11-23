# Android Auto split screen / Coolwalk

## Symptom

On some head units (e.g. Chery Tiggo 7 PHEV, 2025), **video works full-screen** but not in **split dashboard** or **shrunk projection**.

## How AA works

1. **Coolwalk split** — ipanHub is registered as a **navigation** app (NavigationTemplate + `ACCESS_SURFACE`). Video needs a **Surface** from `AppManager.setSurfaceCallback`.
2. When another app owns the **navigation slot** (e.g. Google Maps), ipanHub may appear only as a **media card** with **no surface**. In that case **audio** can still play; the car UI shows *Video area unavailable* with instructions.
3. **Smaller surface** — When ipanHub keeps the nav slot but the host shrinks the surface (split layout), the same `Surface` may be **resized** or **destroyed and recreated**. ipanHub **re-binds** ExoPlayer on every `onSurfaceAvailable`, `onVisibleAreaChanged`, and `onStableAreaChanged`, with **scale-to-fit** letterboxing (no minimum size).

## Debugging

Phone menu → **Copy debug logs** → search for `[car] onSurfaceAvailable`, `onVisibleAreaChanged`, `[surface] attach`.

## DHU

Test with Desktop Head Unit: change display size and multi-pane / simulated split if your DHU build exposes it. Full-screen vs narrow window should both log new dimensions and `[surface] attach … size=WxH`.