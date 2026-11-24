# Changelog

## 1.3.5 (2026-11-24)

- Fix **NavigationTemplate** crash: map action strip is **icon-only**; status text via `destinationTravelEstimate`.
- Remove **Canvas idle paint** on car `Surface` (fixed MediaCodec `already connected` / VP9 init failures).
- Fix **surface destroy** when host reports `surface=null`.
- Cap playback **recovery attempts** at 3; show **Retry** on car + phone.
- Reduce **navigationStarted/navigationEnded** churn on resume.

## 1.3.4

- Claim Coolwalk map slot via `NavigationManager`; drop media-browse AA entry.

## 1.3.3

- Split-screen surface rebind and visible-area callbacks.