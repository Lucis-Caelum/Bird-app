# Bird Play — Android gameplay automation prototype

> **Article ID:** BRD-001  
> **Author:** ChatGPT (OpenAI)  
> **Document status:** Maintained project index  
> **Current app version:** 0.51.0 (versionCode 58) — controller rewritten, see [docs/REWRITE_0.50.md](docs/REWRITE_0.50.md)

Bird Play is an Android prototype that observes a Flappy-style game through screen capture, detects the bird and pipe gaps, predicts the bird's vertical trajectory, and sends touch gestures through Android Accessibility. The objective is to pass as many pipe pairs as possible while keeping the game visible and allowing immediate manual takeover.

## Repository

- GitHub: https://github.com/Lucis-Caelum/Bird-app
- Control core (pure Kotlin, testable without a phone): `app/src/main/java/com/luciscaelum/slayautoplayer/core/`
- Android shell: `GameAccessibilityService.kt` (taps, log), `MediaProjectionCaptureService.kt` (frames)
- Offline simulator: `tools/simulator/`
- APK download branch: `apk-download`
- Current APK: https://raw.githubusercontent.com/Lucis-Caelum/Bird-app/apk-download/Bird-Play.apk

## Documentation

- [Project overview](docs/PROJECT_OVERVIEW.md)
- [Goals and development plan](docs/GOALS_AND_ROADMAP.md)
- [Technical findings and known problems](docs/TECHNICAL_FINDINGS.md)
- [Version 0.50 rewrite: what changed and why](docs/REWRITE_0.50.md)
- [Latest test session log — 2026-11-22 17:36:05](docs/SESSION_LOG_2026-11-22_17-36-05.md)

## Current status at a glance

**0.50.0** replaces the Q-learning/heuristic controller with a model-based one (physics learned online, forward simulation, per-pipe tracking, capture-time timestamps). It is verified only in simulation so far; it has NOT been run on a device. The text below describes the old 0.43.0 behaviour that motivated the rewrite.

Version 0.43.0 fixed an execution-continuity problem: a queued intercept tap should not be discarded merely because a new frame briefly fails to produce another plan. A pending tap for the same tracked pipe is retained when the geometry is trusted and waiting is still predicted to be unsafe; the deadline can be moved earlier but not postponed.

The latest 18-attempt test showed one clearly longer run (7 pipes), three runs with 2 pipes, four runs with 1 pipe, and ten runs with 0 pipes. The main remaining problems are excessive/poorly timed taps, incomplete feedback stabilization between taps, and unstable pipe-gap geometry when detections change between frames.

## Build and installation

GitHub Actions builds the Android debug APK on pushes to `main`. The published APK is placed on `apk-download`. Install the APK on the Android device and grant the overlay and Accessibility permissions requested by the app. Keep the game visible during testing; the automation relies on captured frames.

## Important

This is an experimental prototype, not a finished autonomous player. A successful build only confirms that the app compiled and was published; it does not mean the gameplay controller is reliable. Every gameplay change must be evaluated against unattended test logs.

See the linked documents for the current technical state, test evidence, goals, and next work items.
