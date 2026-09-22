# Tutorial 36 — rejected take

## Take 1 (tenant `Tutorial Demo 2026-09-22 D`, 125.52s, 1920x1080)

Rejected for the same measured reason as tutorial 35's takes: **too much of the
running time is the app's loading shell.**

Scanning every 2 seconds and flagging frames under 60 KB (a near-blank PNG is
the spinner):

```
near-blank seconds: 30 32 50 52 54 56 58 78 100 102 118 120 122
13 of 63 samples
```

The 50–58s stretch is the recognition preview scene and 118–122 is the closing
journals scene.

The scenario itself is sound. Every scene's own QA frame is correct, the
preview listed the eight planned periods with their day counts, and the run
posted them — the flow is the one the narration describes.

## Cause

Not the scenario and not the pages. See `tutorials/qa/review/35/REJECTED.md`
for the measurements: the register page reaches its content in 1.2–1.9 seconds
in a plain Playwright context, but several times that while Playwright is
encoding a 1920x1080 video on a machine whose load average sat between 15 and
28 for the whole session (another agent was running the production e2e suite on
a loop). The recorder starts each scene's clip when the page is created, so any
time the renderer is starved goes into the take as a spinner.

Tutorial 34, the one take that passed, was recorded during a lull at roughly
half that load and scored 1 near-blank sample out of 65.

## To re-record

The seeded state this tutorial consumes is the pending recognition rows, so it
needs a tenant whose draft contract has been posted and whose periods have not
yet been run:

```bash
# fresh tenant, then post the draft (on camera via tutorial 34, or by API)
PROD_BASE_URL=http://localhost:3001 TUTORIAL_TTS_PROVIDER=azure \
TUTORIAL_NAV_TIMEOUT_MS=120000 \
TUTORIAL_SEED_MANIFEST=<manifest>.json \
tutorials/capture-tutorial.sh 36
```

Wait for the machine to be idle first — that is the whole fix.
