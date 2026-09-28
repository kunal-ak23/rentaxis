# Tutorial media retention

All tutorial artifacts stay under this repository so capture failures can be reviewed and resumed:

- `raw/` — Playwright scene recordings (gitignored), kept only until the final video passes QA.
- `work/` — local timing, narration, render intermediates and the recording stack (gitignored).
- `audio-generated/` — narration audio used for capture and final rendering.
- `output/` — final MP4 videos with embedded subtitles and matching `.srt` sidecars. These final deliverables are intentionally visible to version control/workspace backup so they survive environment refreshes.
- `qa/<id>-contact-sheet.jpg`, `qa/<id>-qa.txt`, `qa/<id>-record.log` — QA evidence for the last passing take.

Do not write tutorial media to `/tmp`, `/private` or any system temporary directory; every script defaults to the folders above.

Raw videos are not kept once the final is ready. When a tutorial's QA gates PASS, `record-local.sh` runs
`prune-tutorial-media.sh <id> <slug>`, which removes that tutorial's `raw/tutorial-<id>-*` takes,
`output/<slug>-silent.webm` and `work/<id>/`. Kept: the final MP4, the `.srt`, the narration text, the generated
audio and the QA evidence. A failed or rejected take is never pruned: its raw capture stays, with a `REJECTED.md`
naming the failing step, until a retake passes. The prune script resolves every target with `realpath` and refuses
anything outside `tutorials/raw/`, `tutorials/work/` or the exact silent-webm path.

## Recording stack

Tutorials are recorded against a clean, local-only stack so no test data reaches a published video:
database `rentaxis_tutorials`, a backend jar and a production web build, both built from `git archive HEAD`
into `tutorials/work/stack/` (gitignored), seeded with the fictional **Oasis Crest Properties**
(`tutorials/brand/oasis-crest/`).

```bash
tutorials/recording-stack.sh build     # backend jar + web production build from HEAD (waits for other Gradle runs)
tutorials/recording-stack.sh start     # creates the DB if missing, backend :8084, web :3004
tutorials/recording-stack.sh seed      # seeds Oasis Crest Properties (idempotent; --reset wipes and reseeds it)
tutorials/recording-stack.sh status
tutorials/recording-stack.sh stop      # stops only the processes this script started
```

- Web `http://localhost:3004` (`NEXTAUTH_URL`/`AUTH_URL` set to it, `BACKEND_URL=http://localhost:8084`, local
  NextAuth secret copied from `web/.env.local`); backend `http://localhost:8084`. Override with
  `TUTORIAL_WEB_PORT` / `TUTORIAL_BACKEND_PORT` / `TUTORIAL_DB`. Ports 3000–3003 and 8081–8083 belong to other
  local stacks and are refused.
- Outbound email/SMS/AI are blanked and payments stubbed; blobs go to the local Azurite from `.env.backend`.
- The seed manifest (`tutorials/work/seed/oasis-crest.out.json`) holds local test passwords: never commit or print it.
- After new commits, `build` then `stop` + `start` to record the new code.

## Recording a tutorial locally

```bash
tutorials/record-local.sh <id> [voice] [words-per-minute]   # defaults: en-US-Ava:DragonHDLatestNeural, 140
```

It signs the local super admin into `tutorials/.auth/superadmin-local.json` (gitignored; the helper refuses a
non-localhost URL or a tracked path), runs the proof (validate-only), captures with Azure narration
(`tutorials/.env.local` holds the speech key), renders to `output/<slug>.mp4` + `.srt`, runs the seven QA gates
(`qa-gates.sh`), deletes any draft contracts the takes created, and prunes intermediates on a pass. Review
`qa/<id>-contact-sheet.jpg` before calling a video approved.

Production recordings still use `capture-tutorial.sh` with the default `web/e2e-prod/.auth/superadmin.json`;
`TUTORIAL_AUTH_STATE` overrides the super-admin session for any other environment.

## Recorder layout

`capture/record-tutorial.mjs` is only the runner. Each tutorial's scenes live in `capture/scenarios/<id>.mjs`
(default export `{ role, scenes }`), shared helpers in `capture/lib/` (`context` — argv/env/seed, `page` — waits,
navigation, capture styles, `cursor` — pointer overlay, `proof` — assertions and pacing, `scenes` — scene
constructors, `fixtures` — seed-manifest records, `flows` — reused UI flows, `narration` — timing). Refreshing a
tutorial means editing its scenario file, so several tutorials can be refreshed in parallel.
