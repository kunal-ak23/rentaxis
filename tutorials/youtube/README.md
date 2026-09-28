# YouTube upload kits for the Miftah web tutorials

This directory holds everything needed to upload a tutorial video to
YouTube: metadata (title, description, tags, chapters, playlists) and a
generated thumbnail. Nothing here is video/narration source — that lives in
`tutorials/output/` (rendered MP4 + SRT) and `tutorials/narration/`
(narration scripts), which this tooling only reads.

## Layout

```
tutorials/youtube/
  build-kits.mjs        generator script (this is the only script here)
  kits/<id>.json         machine-readable kit: title, description, tags,
                          chapters, playlists, file paths
  kits/<id>.md            same kit, human-readable (paste-ready description
                           block, tag list, chapter list)
  thumbnails/<id>.png     1280x720 generated thumbnail (Miftah brand: black
                           #1B1B1B background, gold #EEC046 accents, big
                           tutorial number, title, wordmark — text and simple
                           shapes only, no logos)
  uploads.json            upload registry: id -> { youtubeVideoId,
                           uploadedAt, visibility }. Created empty by the
                           generator and filled in by hand (or by an upload
                           script) as videos actually get uploaded. The
                           generator never overwrites an existing
                           uploads.json.
  README.md               this file
```

## What a kit contains

Each `kits/<id>.json` / `kits/<id>.md` pair has:

- **title** — `Miftah tutorial <NN> · <English title>`, capped at 100 chars.
- **description** — capped at 5000 chars: the catalogue's English summary,
  a "Who this is for" line naming roles by their in-app names (Company
  Admin, System Admin, Property Manager, Accountant, Tenant, Company User,
  Security Guard), YouTube chapter markers starting at `0:00` (at least 3,
  each at least 10 seconds), a `Part of the Miftah web course — playlist: …`
  line, and a short Arabic summary line taken from the catalogue.
- **tags** — capped at 500 chars total: `miftah`, `property management`,
  `UAE`, `Dubai`, plus topic-relevant terms (tenancy contract, cheques,
  chart of accounts, maintenance tickets, etc).
- **category** `Education`, **language** `en`, **visibility** `unlisted`,
  **madeForKids** `false`.
- **playlists** — the tutorial's topic playlist (e.g. `Miftah · Leasing`)
  plus `Miftah · Complete web course`.
- **captionsFile** / **videoFile** — paths to the `.srt` and `.mp4` under
  `tutorials/output/`.
- **thumbnail** — path to the generated PNG under `tutorials/youtube/thumbnails/`.

## Where the data comes from

- **Catalogue** (`web/src/lib/tutorials/catalog.ts`) — id, slug, bilingual
  title/description, topic, roles, duration. The script parses this file's
  TypeScript source directly (no `tsx`/build step available locally) and
  never modifies it.
- **Video + captions** (`tutorials/output/<slug>.mp4` + `.srt`) — a kit is
  only generated for an id that has an MP4 rendered.
- **Chapters** — ideally derived from the recorder's per-scene timing log,
  but that log is deleted from disk once a tutorial passes QA (see
  `tutorials/work/infra-report.md`, "Retention"). So chapters are derived
  from the SRT instead: the script picks points evenly spaced through the
  video, then snaps each one to the nearest caption that starts a new
  sentence (a real scene/caption boundary) within a search window, falling
  back to the biggest pause between cues, and finally to the nearest cue
  start. This guarantees chapters start on a caption boundary without
  needing the pruned timing log.
- **Terminology** — copy the script authors itself (title, chapter labels)
  is checked against `web/src/lib/__tests__/terminology.test.ts`'s banned
  words (Renter/Lease) and fails the build if either slips in. Catalogue
  copy is trusted as already correct (it already says Tenant / Tenancy
  Contract / Organisation) but is checked too.

## Regenerating

```bash
cd /Users/kunalsharma/datagami/rentaxis
node tutorials/youtube/build-kits.mjs                # every id with an MP4
node tutorials/youtube/build-kits.mjs --ids 01,10     # just these ids
node tutorials/youtube/build-kits.mjs --no-thumbnails # skip PNG rendering (faster iteration)
```

The script is idempotent and safe to re-run: it overwrites `kits/*.json`,
`kits/*.md` and `thumbnails/*.png` for the ids it processes, but never
touches `uploads.json` once that file exists, and never touches anything
outside `tutorials/youtube/`.

Thumbnails render with the Playwright Chromium browser that ships as a
`web/` dev dependency (`web/node_modules/playwright`); no separate install
is needed as long as `web/`'s dependencies are installed.

Re-run this after every re-record (tutorials get re-recorded on the clean
Oasis Crest stack — see `tutorials/work/infra-report.md`): the video file,
SRT and chapter derivation all depend on the current MP4/SRT pair, so a kit
for an id whose video changed is stale until regenerated.

The script validates everything it generates and fails loudly (non-zero
exit, no partial/silent output) if:

- a tutorial id with an MP4 has no matching `catalog.ts` entry,
- title, description or tags exceed their length caps,
- fewer than 3 chapters are derived, any chapter is under 10 seconds, or
  the first chapter doesn't start at `0:00`,
- generated copy (title, chapter labels) contains "Renter" or "Lease".

## Upload checklist

1. Regenerate kits (`node tutorials/youtube/build-kits.mjs`) so the kit
   matches the current MP4/SRT.
2. Open `kits/<id>.md` — it has the paste-ready title, description block,
   tag list and chapter list.
3. Upload `tutorials/output/<slug>.mp4` to YouTube.
   - Title, description, tags: paste from `kits/<id>.md`.
   - Thumbnail: upload `thumbnails/<id>.png`.
   - Captions: upload `tutorials/output/<slug>.srt` as the English caption
     track.
   - Category: Education. Audience: not made for kids.
   - **Visibility: Unlisted.** Do not publish public yet.
   - Add the video to the playlists listed in the kit (create the playlist
     first if it doesn't exist yet).
4. Record the upload in `tutorials/youtube/uploads.json`:
   `{ "id": "01", "youtubeVideoId": "<video id>", "uploadedAt": "<ISO date>", "visibility": "unlisted" }`.
5. Send the unlisted link to Kunal for review.
6. Once Kunal approves, switch visibility to **Public** on YouTube and
   update `uploads.json`'s `visibility` field to `"public"`.
7. Only after a tutorial is re-recorded should its kit, thumbnail and
   YouTube listing be regenerated/re-uploaded — the id in `uploads.json`
   stays the same; replace the video via YouTube's own "replace video"
   flow or re-upload and update `youtubeVideoId`, whichever the team
   prefers at the time.
