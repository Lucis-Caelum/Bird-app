# Bird Play (Android prototype)

Version 0.1.0 is an instrumentation build, not a finished autonomous player.

## Build
GitHub Actions builds a debug APK on every push to main and on manual workflow dispatch. Download the artifact named **Bird-Play-debug** from the Actions run.

## Setup on Android
1. Install the APK.
2. Open the app and grant **Display over other apps**.
3. Open Accessibility settings and enable **Bird Play control**.
4. Return to the app and choose **Show floating panel**.
5. Open the supported mini-game. The panel has pause/manual-takeover/stop controls.

## Current limitations
- Autoplay is intentionally disabled by default.
- Score OCR and obstacle recognition are not implemented or calibrated.
- The game is rendered in a web canvas, so accessibility text may not expose the score.
- Do not treat this build as capable of reaching a target score. A gameplay capture from the target phone is required to calibrate the vision logic and test tap timing.

The accessibility service only exposes a guarded gesture method; the overlay does not send automatic taps.
