# Tutorial 37 — rejected take

## Take 1 (tenant `Tutorial Demo 2026-09-22 D`, 121.80s, 1920x1080)

Rejected for the same measured reason as tutorials 35 and 36: **too much of the
running time is the app's loading shell.**

Scanning every 2 seconds and flagging frames under 60 KB:

```
near-blank seconds: 30 54 56 74 94 96 98 100 112 114
10 of 61 samples
```

This is the lightest of the four scenarios — every scene is read-only — and it
still lost a six-second stretch at 94–100s, the scene that opens the journal
behind a ledger row.

## Cause

Machine contention, measured rather than assumed. See
`tutorials/qa/review/35/REJECTED.md`: pages reach their content in 1.2–1.9
seconds in a plain Playwright context, but several times that while a 1920x1080
video is being encoded on a machine at load average 15–28. Playwright starts a
scene's clip when the page is created, so every starved second is a spinner in
the take.

## To re-record

Nothing in the scenario needs changing, and nothing in it changes state — this
is the safest of the four to retake, against any tenant whose prepared renter
has a returned cheque in the ledger:

```bash
PROD_BASE_URL=http://localhost:3001 TUTORIAL_TTS_PROVIDER=azure \
TUTORIAL_NAV_TIMEOUT_MS=120000 \
TUTORIAL_SEED_MANIFEST=<manifest>.json \
tutorials/capture-tutorial.sh 37
```

Wait for an idle machine first.
