# Project overview — Bird Play

> **Article ID:** BRD-002  
> **Author:** ChatGPT (OpenAI)  
> **Document status:** Project description  
> **Last updated:** 2026-11-22  
> **Current app version:** 0.43.0 (versionCode 56)

## 1. What the project is

Bird Play is an Android automation prototype for a Flappy-style game. It observes the live game screen, estimates the bird's position and vertical movement, detects pipe pairs and their safe opening, predicts future motion, and uses Android Accessibility gestures to tap the screen.

The project is intentionally built around visible, on-device gameplay. The user can watch the game and retain manual control rather than running an invisible simulation.

## 2. Main objective

Build an autonomous controller that can consistently pass pipe pairs over long, unattended sessions. The controller must:
- recognize the bird and the correct upcoming pipe pair;
- estimate the opening between the upper and lower pipe;
- estimate horizontal pipe speed and time until the bird reaches it;
- predict the bird's vertical position and velocity under WAIT and TAP actions;
- select a safe tap schedule, including the option to do nothing;
- execute actions at the right time while accounting for Android gesture and game-physics latency;
- learn from confirmed pipe passes and failed attempts without corrupting its physics calibration.

The quality metric is actual passed pipes per attempt, not how many plans the algorithm generates.

## 3. Android architecture

The repository is a small native Android/Kotlin app.

| File | Responsibility |
|---|---|
| `GameAccessibilityService.kt` | Main control loop: frame processing, vision/detection, pipe tracking, trajectory prediction, tap scheduling, feedback, logging, and Q-learning state |
| `MediaProjectionCaptureService.kt` | Screen-capture service used to obtain frames from the visible game |
| `OverlayService.kt` | Floating controls and on-screen status/monitoring interface |
| `MainActivity.kt` | App entry point and setup/control UI |
| `LogActivity.kt` | Session-log viewing/export UI |
| `app/src/main/AndroidManifest.xml` | Android components and permissions |
| `.github/workflows/build-apk.yml` | Automated APK build and publishing workflow |

Build configuration: Android Gradle Plugin project, Kotlin, compile/target SDK 35, minimum SDK 26, Java/Kotlin target 17.

## 4. Main control pipeline

1. **Capture:** acquire a frame from the screen-capture service.
2. **Detect:** estimate the playfield, bird bounds/center, pipe bounds, and gap.
3. **Track:** associate observations across frames and estimate pipe movement/speed.
4. **Predict:** estimate bird trajectory under gravity and calibrated tap impulse.
5. **Plan:** evaluate WAIT and one-or-more-tap schedules against the safe opening.
6. **Schedule:** account for estimated response latency and gesture availability.
7. **Execute:** dispatch an Accessibility gesture.
8. **Verify:** compare observed movement with predicted movement; update flap/latency estimates only when the impulse is sufficiently attributable.
9. **Learn and log:** update Q-learning data after pipe passes/deaths and record diagnostic events.

## 5. Important implementation concepts

- **Pipe track ID:** identity assigned to the currently tracked pipe pair.
- **Gap geometry:** detected top and bottom Y coordinates of the opening.
- **Safe center:** gap interval reduced by margins for the bird's dimensions and uncertainty.
- **Trajectory prediction:** projected bird position/velocity over time.
- **Tap response latency:** delay between issuing a gesture and observing its physical effect in the game.
- **Pending tap feedback:** observation window used to verify the effect of a specific tap.
- **Intercept schedule:** a delayed tap intended to place the bird safely when it reaches a pipe.
- **Q-learning table:** persisted state/action values updated from gameplay outcomes.

## 6. Current baseline

As of 2026-11-22, the published app is version 0.43.0. The latest test log is `Bird-Play-session-2026-11-22_17-36-05.txt` and contains 18 attempts.

Observed result:
- 18 attempts;
- 8 attempts passed at least one pipe;
- 16 pipe passes in total;
- 1 best attempt passed 7 pipes in 9.755 seconds;
- the next-best attempts passed 2 pipes;
- 101 automatic taps across the session.

This is still an unstable prototype. The best attempt proves that the controller can sometimes sustain a run, but the overall distribution remains poor.

## 7. Repository and artifact links

- Repository: https://github.com/Lucis-Caelum/Bird-app
- Current APK: https://raw.githubusercontent.com/Lucis-Caelum/Bird-app/apk-download/Bird-Play.apk
- Build workflow: https://github.com/Lucis-Caelum/Bird-app/actions

## 8. Scope and caveats

This document describes the current implementation and observed behavior, not a guarantee of performance. The log and source code are the authority for diagnosing individual failures. A successful APK build is not a gameplay validation.
