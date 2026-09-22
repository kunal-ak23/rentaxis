# Tutorial 35 review

- Tutorial: Register and clear cheques
- Environment: local stack (backend `:8081` rebuilt from HEAD, web `:3001`),
  synthetic tenant `Tutorial Retake 35`, seeded fresh — this tutorial bounces a
  cleared cheque, which is not undoable, so it needs its own tenant per take
- Narration: Azure Speech `en-US-Harper:MAI-Voice-2`, style `hopeful`, 125 wpm
- Final video: `tutorials/output/35-register-and-clear-cheques.mp4`
- Subtitles: `tutorials/output/35-register-and-clear-cheques.srt`
- Duration: 135.88 seconds; 11,191,332 bytes; 1920x1080; 35 subtitle cues
- Machine load average at capture: 14.54

## Proof before the capture

`web/e2e/finance/accounting-v2.spec.ts --project=tenant-admin`: **13 passed
(1.2m)**, including *the register deposits and clears one cheque, returns
another and replaces it*. Scenario dry run
(`TUTORIAL_CAPTURE_VALIDATE_ONLY=1`) on a separate spent tenant, so the capture
tenant stayed pristine: `scenario_validation=passed`, all seven scenes.

## Frame checklist

| Frame | 1920x1080 | Rendered | No error | English | No cheque image / phone / bank a/c | Subtitle matches |
|---|---|---|---|---|---|---|
| 5s | yes | the register, summary tiles and aging strip | yes | yes | yes | yes |
| 40s | yes | the collection page, batch deposited | yes | yes | yes | yes |
| 80s | yes | the register, Clear callout | yes | yes | yes | **trails by ~4s** (see below) |
| 105s | yes | register narrowed to A-102, 300201 BOUNCED, tiles reading 15,500.00 | yes | yes | yes | **trails by ~4s** |
| 133s | yes | Penalties queue: *"Cheque 300201 returned (BOUNCE), bounce #2 on this lease"*, 1,000.00 | yes | yes | yes | yes |

Result: **approved, with two residuals recorded below rather than hidden.**

## Residuals a reviewer may want to overrule

1. **Scene seams trail the narration by about four seconds.** Scene time is
   split by weight, so a scene whose actions run long pushes its callout past
   the sentence it illustrates. At 80s the Clear callout is up while the
   narration has reached the bounce; at 105s the bounce callout is up while the
   narration has reached the replacement. The screen is never showing something
   the narration contradicts — it is showing the step just completed — but the
   two are not in lockstep. Fixing it means retuning the weights by trial,
   which costs a fresh tenant per attempt.
2. **9 of 68 sampled frames are the loading shell**, the longest run about six
   seconds at the bounce scene's seam (84–88s). Better than the rejected take
   (15 of 63, with an 18-second stretch) and worse than tutorials 34 (1 of 65)
   and 37 (3 of 59). The bounce scene is the slowest in the set: every
   transition on the register refreshes the list, the summary tiles and the
   aging strip together. It is already narrowed to one unit first, which is
   what brought this down from the rejected take.

## Notes

- The bounce is performed on camera. The seed leaves its returned cheque
  already REPLACED, and `/cheques/return-replace` lists `status: "BOUNCED"`
  only — so without a live bounce that scene would hold on an empty page.
  Bouncing Fatima's second cheque also trips the per-lease threshold of 2 and
  puts a real row in the Penalties queue, which the closing scene needs.
- The replacement dialog is filled but not confirmed: confirming closes it and
  leaves the page empty for the hold, and the filled dialog is what shows the
  two rows and the residual line the narration is explaining.
- No cheque image is opened at any point.
