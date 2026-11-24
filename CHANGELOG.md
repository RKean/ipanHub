# Changelog

## 1.3.7 (2026-11-24)

- AA launcher: drop `media` from `automotive_app_desc`; deny Gearhead MediaBrowser root so the icon opens **NavigationTemplate** / surface (not browse UI).
- Removed misleading media-root subtitle.

## 1.3.6 (2026-11-24)

- Restore **automotive_app_desc `media`** + MediaBrowser root (AA launcher for sideload).
- **minCarApiLevel** 6 (Car App Library 1.7).
- Search suggestions + queue mini-panel **auto-hide**; dismiss + FAB restore.

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