# REJECTED 38 take 1 (2026-09-29)

All 7 QA gates passed, but the frame review fails: scenes 2, 3 and 5 overran by about 70 s
each (scene starts 17.1 / 87.9 / 177.6 / 245.9 s against a 142 s narration), so the video
was cut long before the Tenant Ledger, Trial Balance and P&L scenes, and from cue 7 on the
picture does not show what the voice says.

Cause: three `pointAt` targets did not exist in the page (the journal "Total" row, and two
headings the app renders in upper case with CSS: "JOURNAL VOUCHER", "DAYS"). In capture
mode `glideTo` waits the full navigation timeout (30 s) for each; validate-only skips
pointers, so the proof could not see it.

Fixes: `pointAt` now fails the proof when its target does not resolve (lib/cursor.mjs), the
three locators were corrected, and the capture reuses the timing audio the cues were measured
on (TUTORIAL_NARRATION_AUDIO), because a fresh Azure synthesis came out 4.8 s shorter.
