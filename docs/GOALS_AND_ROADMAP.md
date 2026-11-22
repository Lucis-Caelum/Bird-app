# Goals and development roadmap

> **Article ID:** BRD-003  
> **Author:** ChatGPT (OpenAI)  
> **Document status:** Active development plan  
> **Last updated:** 2026-11-22

## 1. What we want to achieve

The target is a reliable Android Flappy-style game player that can pass long sequences of pipes without human assistance.

Success means:
- repeated long runs, not one lucky run;
- low variance between attempts;
- no unnecessary bursts of taps;
- correct behavior for both high and low openings;
- stable detection while two pipe pairs are visible or overlap horizontally;
- reliable feedback between a tap and the resulting vertical impulse.

A single high score is not sufficient evidence. We need repeatable results over multiple unattended attempts.

## 2. What has been explored so far

### Vision and pipe detection
- Frame-based bird and pipe recognition.
- Detection of orange triangular crystals at pipe ends as additional evidence.
- Merging vertical runs and extracting candidate openings.
- Tracking a pipe pair across frames.
- Caching/reusing recent gap geometry when a new observation is rejected.
- Pipe-pass detection based on the trailing edge clearing the bird.

### Physics and trajectory
- Estimating vertical velocity and gravity from successive frames.
- Calibrating tap impulse from confirmed vertical response rather than gesture completion alone.
- Estimating tap-to-response latency.
- Predicting WAIT and TAP trajectories at the pipe crossing.
- Evaluating candidate schedules with one, two, or three taps, plus bounded rescue behavior.

### Control and scheduling
- Pre-pipe altitude guard.
- Collision-intercept planner.
- Receding-horizon replanning.
- Delayed Accessibility gesture dispatch.
- Retention of a committed scheduled tap in version 0.43.0 when small frame-to-frame changes temporarily remove the newly calculated plan.
- Persistent learning table updated from pipe passes and deaths.

### Research direction
The general approach is model-predictive control: predict possible action sequences, execute only the next action, observe the new state, and replan. Our implementation has an additional difficulty: it must infer state from noisy screen pixels and compensate for Android capture/gesture latency.

## 3. Current findings from the latest session

The latest 18-attempt session is weak overall:
- 10 attempts passed zero pipes;
- 4 attempts passed one pipe;
- 3 attempts passed two pipes;
- 1 attempt passed seven pipes.

There are 16 passes total and 101 automatic taps. The seven-pipe run lasted 9.755 seconds. This suggests that a viable control sequence is sometimes found, but the controller does not reproduce it reliably.

Three concrete failure classes are visible:

1. **Wrong-direction/interpipe correction.** The cruise controller can tap to climb even though the next opening is low and the bird is already moving upward rapidly.
2. **Tap stacking.** A new tap can be dispatched while the previous tap's physical response is still being measured or has timed out. The vertical effects accumulate and can push the bird toward the upper boundary.
3. **Unstable gap geometry.** Repeated detections sometimes differ from the cached opening by more than the current rejection threshold. The controller then continues using stale geometry instead of accepting a plausible updated boundary.

See [Technical findings](TECHNICAL_FINDINGS.md) and [the latest session log](SESSION_LOG_2026-11-22_17-36-05.md).

## 4. Proposed next development steps

These are proposals, not changes already made. Version 0.44.0 has not been implemented or approved yet.

### Priority 1 — prevent tap stacking
- Introduce a physics-response lock, not merely a gesture-completion lock.
- Do not issue a second tap until the previous impulse is confirmed, rejected, or explicitly timed out with a safe fallback.
- If a feedback sample is pending, do not schedule a second rescue tap based on an unverified trajectory.
- Log each tap lifecycle: planned → dispatched → gesture completed → impulse confirmed/timed out → next action allowed.
- Keep the hard safety cap on taps per pipe, but do not treat that cap as a substitute for response stabilization.

### Priority 2 — remove harmful interpipe lift
- Replace a fixed target-height tendency with a prediction of the next known gap.
- If the bird is already rising and the next gap is low, prefer WAIT; do not tap merely to maintain a generic altitude.
- Permit a cruise tap only when the predicted no-tap trajectory is actually unsafe against the next relevant opening or playfield boundary.
- Distinguish “need to avoid falling out of the playfield” from “need to align with the next pipe gap.”

### Priority 3 — bound rescue plans
- Reject rescue plans whose predicted miss is far beyond a configurable maximum.
- Never dispatch a tap when the plan's own predicted bird position is clearly on the wrong side of the opening.
- In particular, a plan with a predicted miss around 1,000 px must not be accepted as a useful rescue.
- Keep separate thresholds for normal safe plans and last-resort rescue plans.

### Priority 4 — improve multi-pipe geometry
- Detect and track each pipe pair independently when more than one pair is visible.
- Avoid combining top and bottom boundaries from different pipe pairs.
- Use spatial continuity and per-pipe identity to associate upper/lower pipe sections.
- If geometry changes, distinguish a real boundary update from a noisy partial detection.
- Prefer a short-lived uncertainty state over silently holding a stale gap indefinitely.

### Priority 5 — validate with controlled tests
For each build:
1. run 15–20 unattended attempts;
2. compare zero-pass rate, median passes, best passes, taps per passed pipe, and unattributed impulses;
3. inspect every attempt with repeated taps or geometry rejection;
4. change one major control subsystem at a time;
5. do not call a version successful based on compilation alone.

## 5. Acceptance criteria for the next iteration

A build should not be considered an improvement unless a comparable test batch shows:
- fewer zero-pass attempts;
- more consistent multi-pipe runs;
- no repeated tap dispatch while feedback from a previous tap is unresolved;
- no obvious upward tap burst when the next gap is low;
- fewer stale-gap rejections near the bird;
- no increase in collisions with the upper playfield boundary.

The first target is reliable passage of several consecutive pipes across most attempts. Only after that should we optimize high scores.
