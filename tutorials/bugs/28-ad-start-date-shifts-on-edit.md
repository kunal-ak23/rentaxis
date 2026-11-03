# 28: editing a promotion ad shows (and re-saves) its start date one day early

**Affects:** tutorial 28 (Promotions) — the "edit it and clear Active" step was cut from the take; recorded scope ends at
the Ads list filter.

**Found:** 2026-10-05, recording stack built from fcf0160, Oasis Crest Properties, Company Admin.
Page: More → Promotions → Ads → Edit.

## What happens
An ad saved with Starts 05/10/2026, Ends 04/11/2026 reopens in Edit as Starts **04/10/2026**, Ends 04/11/2026.
Saving the edit (e.g. to clear Active and pause it) writes the earlier start, so every edit moves the start back a day.

## Why
`web/src/app/[locale]/dashboard/promotions/_components/AdEditor.tsx`: save sends
`new Date(\`${startsAt}T00:00:00+04:00\`).toISOString()` (= previous day 20:00Z), load reads
`ad.startsAt.slice(0, 10)` — the UTC date. The end date survives only because 23:59:59+04:00 is still the same UTC day.

## Expected
Edit shows the dates that were entered (convert the stored instant to the Gulf date on load), and a save without
date changes leaves them unchanged.
