# Tutorial media retention

All tutorial artifacts stay under this repository so capture failures can be reviewed and resumed:

- `raw/` — Playwright scene recordings, retained until the corresponding final video is approved.
- `work/` — local timing, narration, and render intermediates for a reproducible edit.
- `audio-generated/` — narration audio used for capture and final rendering.
- `output/` — approved MP4 videos with embedded subtitles and matching `.srt` sidecars. These final deliverables are intentionally visible to version control/workspace backup so they survive environment refreshes.

Do not write tutorial media to `/tmp` or a system temporary directory. After final-media QA passes, explicitly remove only the reviewed raw and work folders for that tutorial; retain the final MP4, `.srt`, narration text, and approved QA evidence.
