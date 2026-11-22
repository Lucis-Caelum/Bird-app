# Technical findings and known problems

> **Article ID:** BRD-004  
> **Author:** ChatGPT (OpenAI)  
> **Document status:** Evidence-based issue register  
> **Last updated:** 2026-11-22  
> **Observed build:** 0.43.0

## 1. Executive diagnosis

The latest log shows that the main problem is not simply a lack of taps. The controller sometimes taps in the wrong direction, sends another tap before the previous physical response is resolved, and holds gap geometry that the detector has repeatedly rejected.

The user's observation that taps seem to “stack” and drive the bird into the upper boundary is consistent with the event sequence in the log.

## 2. Issue A — tap effects stack before feedback stabilizes

**Evidence from attempt 17, pipe track 5:**
- Tap 96 was dispatched at 95,422 ms.
- Its physics response was confirmed at 95,589 ms.
- Tap 97 was dispatched at 95,624 ms, while the trajectory was already moving upward quickly.
- Tap 97 feedback remained pending for multiple frames and timed out at 96,333 ms.
- Tap 98 was dispatched at 96,209 ms while feedback for tap 97 was still pending. The log explicitly says `TAP_FEEDBACK_UNTRACKED ... PREVIOUS_SAMPLE_PENDING`.
- Another rescue tap, 99, was dispatched at 96,411 ms after tap 97 had timed out.
- The pipe budget reached 4/4, while the predicted miss was hundreds of pixels.

This confirms that the controller's current “spacing” and per-pipe tap budget do not guarantee that the previous impulse has settled before another tap is issued.

**Likely gameplay effect:** upward impulses accumulate. The bird can continue climbing even when the next safe opening is low, eventually colliding with the upper pipe or upper playfield boundary.

**Required fix:** add a response-stabilization gate. Gesture completion is not enough; the controller must account for the pending physical response before dispatching another tap.

## 3. Issue B — interpipe cruise tap can move away from a low next gap

**Evidence from attempt 7:**
- At 33,194 ms, the bird was at Y=1353 with vertical velocity approximately -0.036.
- The cruise controller sent tap 31 with reason `INTERPIPE_LIFT_NEEDED`.
- The next pipe was detected at 33,277 ms with gap 1446..2232 — a low opening.
- At 33,359 ms, the confirmed tap response showed vertical velocity around -1.974, meaning the bird was rising rapidly.
- The planner then selected `BEST_EFFORT_SINGLE_TAP` with candidate miss 1037 px and mean miss 788 px.
- It dispatched tap 32 at 33,397 ms anyway.
- Later predictions showed the bird continuing far above the opening.

This is a direct mismatch between the direction of the correction and the next gap's location.

**Required fix:** interpipe cruise should target the next known gap, not a generic altitude band. If the next gap is low and the bird is already rising, WAIT should normally be preferred.

## 4. Issue C — rescue plan can be dispatched despite a huge predicted miss

The attempt 7 intercept log reports:
- gap: 1446..2232;
- candidate predicted position/range: around 745..838;
- candidate miss: 1037 px;
- mean miss: 788 px;
- mode: `BEST_EFFORT_SINGLE_TAP`.

The controller is explicitly aware that the predicted trajectory is nowhere near the safe interval, yet still dispatches the tap.

**Required fix:** enforce an absolute rescue-miss limit and a directional safety check. If a tap cannot plausibly improve the collision outcome, do not send it. “Best effort” must not mean “send an action even when the model predicts it will fail badly.”

## 5. Issue D — gap geometry rejection and stale geometry reuse

The session contains 41 `GAP_GEOMETRY_REJECTED` events.

Examples:
- At 33,358 ms: detected gap 1446..2079, held gap 1446..2232, difference 153 px.
- At 33,450 ms: detected 1446..2058, held 1446..2232, difference 174 px.
- At 33,516 ms: detected 1446..2070, held 1446..2232, difference 162 px.
- At 95,677 ms: detected 750..1413, held 750..1536, difference 123 px.

The system repeatedly rejects a newly observed lower boundary and continues using the older, larger gap. Some rejection is appropriate for noisy frames, but repeated disagreement over several consecutive frames is evidence that the cached value may be stale or the detector is mixing pipe geometry.

**Required fix:**
- track upper and lower pipe boundaries per pipe identity;
- require temporal consistency across a short sequence instead of one-frame acceptance/rejection;
- distinguish partial occlusion from a real gap change;
- avoid assembling a gap from different pipe pairs when they overlap horizontally;
- log why each boundary was accepted, rejected, or retained.

## 6. Issue E — repeated schedule retiming

The log contains 62 `INTERCEPT_DISPATCH_CANCEL` events. Many are `REPLANNED_EARLIER`, which is an intentional behavior in 0.43.0: the schedule is moved earlier when a new prediction says the action should happen sooner. This is not automatically a defect.

However, frequent cancel-and-reschedule cycles make it important to verify that:
- a committed action is not lost during retiming;
- only the same pipe track can update the commitment;
- the final gesture is dispatched once;
- a schedule is invalidated only for a documented safety/lifecycle reason.

The 0.43.0 log does show `INTERCEPT_DISPATCH_KEEP` and `INTERCEPT_PLAN_RETAINED`, so the new retention behavior is active.

## 7. Issue F — pipe crossing and apparent pipe overlap

The user reports that when the bird crosses between pipes, the visualized/observed pipe edges seem to overlap and the bird collides with the upper boundary.

The log supports geometry instability, but it does not by itself prove that the detector literally merges two physical pipe objects into one. The 41 geometry rejections and stale-gap reuse make this a plausible explanation, not a proven one.

**Required investigation:**
- inspect frames with two pipe pairs visible at once;
- log each pipe pair's candidate top and bottom independently;
- preserve stable track IDs as pipes move across the screen;
- compare the selected gap to the actual visible gap in the same frame;
- do not combine boundaries from separate pairs.

## 8. Priority order

1. Stabilize tap feedback and block stacked taps.
2. Remove generic interpipe lift when the next gap is known and low.
3. Reject rescue plans with excessive predicted miss.
4. Improve per-pipe gap association and temporal geometry validation.
5. Revisit schedule retiming only after the above are addressed.

## 9. Evidence boundaries

Confirmed by log:
- taps can be sent while feedback from a previous tap is pending;
- a cruise tap was sent before a low next gap, followed by a large upward velocity;
- a rescue plan with a very large predicted miss was dispatched;
- repeated gap geometry rejections occurred.

Not yet proven:
- that two separate pipe objects are literally merged by the detector;
- that every upper-boundary collision was caused by tap stacking rather than another vision/physics error.

The next test should add frame snapshots or richer per-track geometry diagnostics around those events.
