# Tutorial 35 — rejected takes

Three takes were rejected for the same symptom, and the cause turned out not to
be the one the first two suggested.

## Take 1 (tenant `…-09-22 C`, 128.80s) — rejected

Sampling every 2 seconds and flagging frames under 60 KB (a near-blank PNG is
the app's loading spinner), **15 of 65 samples were the loading shell**,
including an unbroken stretch from 82s to 92s:

```
near-blank seconds: 24 26 42 44 46 62 64 66 82 84 86 88 90 92 102
```

First hypothesis: cold `next dev` route compilation. Tutorial 34, whose routes
the dry runs had already compiled, scored **1 near-blank sample out of 65** on
the same scan, which fitted. Every route the scenario visits was warmed
(`warm-routes.mjs`, run against a disposable tenant — compilation is per route,
not per tenant) until each answered in 1.2–2.3 seconds.

## Take 2 (tenant `…-09-22 D`) — aborted

`page.goto` timed out after 30s navigating to `/en/dashboard/finance/cheques`
in scene 5, with the machine at load average 18.7. No MP4 was produced. The
recorder's navigation ceiling is now `TUTORIAL_NAV_TIMEOUT_MS` (default
unchanged at 30s) so a loaded machine cannot throw away a take and the seeded
state it consumes.

## Take 3 (tenant `…-09-22 D`, 125.72s) — rejected

Warming changed nothing: **15 of 63 samples** near-blank, 80s–98s unbroken.

Measured rather than guessed:

- The register page, loaded in a plain Playwright context at load average 27,
  reaches its summary tiles in **1.2–1.9 seconds** over three runs.
- Scene 5's own clip is **31.08s**, blank for its first ~14s, skeleton until
  ~20s, real content from ~24s.

So the page is not slow; the page is slow **while a 1920x1080 video is being
encoded on a saturated machine**. Another agent was running the production e2e
suite on a loop throughout; load average sat between 17 and 28. Tutorial 34,
which passed, was recorded when the load was roughly half that.

The scene also overran its share of the narration (weight 55 of 370 ≈ 19s
against ~30s of actual actions), which is why the concatenated take is longer
than the audio and the closing scene is trimmed.

**Action:** re-record on a quiet machine. Nothing in the scenario is at fault —
every selector resolves and the end state of every scene is correct (scene 5's
own QA frame shows cheque 300201 BOUNCED and the tiles reading 15,500.00).
