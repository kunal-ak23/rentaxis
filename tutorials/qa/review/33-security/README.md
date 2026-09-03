# Tutorial 33 review log

The security workflow was exercised against the synthetic RentAxis Tutorial Studio tenant with a newly created and manager-approved pass for each attempt. The production scan endpoint returned `ALLOWED` in the direct API check, and the corrected integration harness completed the board → scan → verdict → approvals → walk-in route sequence.

Rejected raw takes:

- `attempt-camera-init-failed.mp4` — camera permission/plugin startup contaminated the capture.
- `attempt-camera-failure-corrected.mp4` — emulator camera initialization returned to the launcher.
- `attempt-capture-seam-failed.mp4` — emulator/app session reset during capture.
- `attempt-diagnostic-passed.mp4` — harness passed but code-entry timing was not visually populated.
- `final-approved-silent.mp4` — recorder began before the cold start and contained launcher/splash frames.
- `attempt-emulator-wellbeing-failed.mp4` — Android displayed “Digital Wellbeing isn’t responding”.

No final MP4 was published from these takes. The next capture should use a stable physical/device emulator or a pre-warmed dedicated runner, then repeat raw visual review before narration.
