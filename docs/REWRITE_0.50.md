# Version 0.50 — controller rewrite

## Why 0.43 played badly (from BRD-005 and the code)
1. **Capture was slow and stale**: frames throttled to 80 ms, each copied into a Bitmap; decisions used processing-time clocks, so the controller reacted to a picture that was already 100+ ms old.
2. **Taps stacked**: 55 ms gesture strokes, feedback gating that timed out, "rescue" taps even when the planner itself predicted a huge miss.
3. **Unstable geometry**: gap edges rejected/held (41 `GAP_GEOMETRY_REJECTED`), only the nearest pipe tracked, so the bird could not prepare for the next gap.
4. **Q-learning** with ~1 sample per state added noise rather than skill.

## What 0.50 does
```
ImageReader (every frame, raw RGBA, Image.timestamp)
  -> FrameAnalyzer      all visible pipes + bird, no Bitmap copies        (core/FrameAnalyzer.kt)
  -> PipeTracker        per-pipe identity, temporal median, spread, speed  (core/PipeTracker.kt)
  -> BirdTracker        900 ms ring buffer, fixed-g quadratic fit -> y, v  (core/Physics.kt)
  -> Calibrator         learns g, flap speed, tap latency online           (core/Physics.kt)
  -> Controller         floor-line rule + two forward simulations
                        (wait vs tap-now) as veto/override, min 110 ms
                        between taps, pending taps are modelled            (core/Controller.kt)
  -> dispatchGesture    12 ms stroke
```
- Time everywhere is **capture time**; the pipeline delay (now − capture) is compensated in the prediction.
- Learned physics (normalised by field size) is saved in SharedPreferences `bird_play_physics_v2` and used as priors next time.
- Priors for a first run come from the 0.43 log (flap speed ≈ −1.9 px/ms, field ≈ 2300 px): `AutoPilot.DEFAULT_*`.

## Verification done (no phone available)
`tools/simulator` — see its README. Pure core: 39/40 full 40-pipe runs with noisy detections, latency 110 ms, wrong priors. End-to-end (rendered raw frames -> analyzer -> pilot): 22/24 runs of 40 pipes.

## NOT verified
- Android glue was type-checked only against stubs; it has not been built with the Android SDK or run on a device.
- Real game physics, colours of the pipes/bird in your skin, and the "tap to start" behaviour are assumptions taken from the old code/logs.

## What to send back after testing
The in-app log (`Журнал`): lines `ATTEMPT_START/END`, `TAP … reason y v age`, `PERF fps analyzeMs ageMs`. `PERF fps` should be ≈ the screen refresh rate and `analyzeMs` < 8; `ageMs` is the capture→decision delay.

## Update after the first real screenshot (0.51)
Checked the detector against a real screenshot of the game (1158x2576):
- **Crystal tips are walls.** The gap was measured 741 px instead of ~504 px because only the stone body counted. Now the crystal tip is added while it stays connected to the stone (<=3 empty rows), so floating bonus crystals / potions inside the gap are NOT treated as walls.
- **Pipe edges**: removed the over-wide "crystal extension" (pipe was 318 px wide instead of 175).
- **Bird detection**: the dark bonus crystal near the bird inflated the bird box (216x336 instead of ~111x180). The bird is now the biggest connected cluster and clusters far from its usual x are ignored.
- **Hit box**: the detector sees only the hoodie, the real sprite (rocket, legs) is bigger. Hit box = detected box x 1.3 by default; adjustable in the app (100–200%). Every finished attempt logs `nearEdge=[TOP|BOTTOM margin=... ]` — the closest approach to a pipe edge in the last 2 s — to tune this.
- **Physics learning**: gravity is now learned from ballistic segments only (no tap takes effect inside the window); default prior g = 5e-6 * fieldH, accepted range 0.2x–6x.
- **UI**: new main screen with setup status, hit-box slider, compact floating panel (state, attempt, pipes, fps/latency), log screen with attempt summary and copy button.

Simulator (realistic proportions: bird ~8–11% of field height, gap ~22%): with learned/saved physics 40/40 and 33/40 full 40-pipe runs for hit boxes 180/240 px; first-ever attempt with wrong priors 34/40 and 23/40. Rendered-frame end-to-end: 10/12.

## 0.52.0 — ранняя гибель у первой трубы
Причина по журналу 0.51.0: хитбокс ×1.3 и большие запасы оставляли в щели полосу уже, чем высота прыжка (~250 px) — план был невыполним. Также первая калибровка задержки тапа пропускалась.
Изменения: хитбокс по умолчанию 100% (слайдер 60–160%, новый ключ настроек), запасы 3% + 5 px у труб, +7% снизу, запасной режим «в середину» при невыполнимом плане, калибровка задержки уже по первому тапу.
