# Tutorial 36 review

- Tutorial: Month-end recognition
- Environment: local stack (backend `:8081` rebuilt from HEAD, web `:3001`),
  synthetic tenant `Tutorial Retake 36b`. The tutorial posts the pending
  periods, which is not undoable, so it needs its own tenant per take; the
  prepared draft contract was posted through the API off camera to create the
  eight pending periods the run closes.
- Narration: Azure Speech `en-US-Harper:MAI-Voice-2`, style `hopeful`, 125 wpm
- Final video: `tutorials/output/36-month-end-recognition.mp4`
- Subtitles: `tutorials/output/36-month-end-recognition.srt`
- Duration: 127.60 seconds; 9,821,624 bytes; 1920x1080; 33 subtitle cues
- Machine load average at capture: 15.19

## Proof before the capture

`web/e2e/finance/accounting-v2.spec.ts --project=tenant-admin`: **13 passed
(1.2m)**, including *running recognition to last month-end posts this contract
CIL by CIL*. Scenario dry run (`TUTORIAL_CAPTURE_VALIDATE_ONLY=1`) against a
separate spent tenant, so this tenant's pending periods survived for the take:
`scenario_validation=passed`, all six scenes.

## Frame checklist

| Frame | 1920x1080 | Rendered | No error | English | No renter phone / bank a/c | Subtitle matches |
|---|---|---|---|---|---|---|
| 5s | yes | the posted contract's recognition schedule | yes | yes | yes | yes |
| 40s | yes | Jan–Aug Posted with CIL numbers, Sep–Dec Planned, Σ 85,000.00 *"Matches the contract rent."* | yes | yes | yes | yes |
| 88s | yes | *"Recognition run — POSTED 8, AMOUNT 73,232.91"*, pending now 0 | yes | yes | yes | yes |
| 105s | yes | General Ledger filtered to Advance Rent, balance falling entry by entry | yes | yes | yes | yes |
| 124s | yes | Journal Vouchers filtered to CIL, every row POSTED | yes | yes | yes | yes |

Result: **approved.**

### The check this tutorial exists for

Spec D13: **every visible CIL entry date is a month end, never the 1st.**

- Recognition schedule (40s): periods end 31/01, 28/02, 31/03, 30/04, 31/05,
  30/06, 31/07, 31/08.
- General Ledger (105s): CIL rows dated 31/01, 28/02, 31/03, 30/04, 31/05,
  30/06, 31/07.
- Journal Vouchers (124s): DOC DATE reads 2026-08-31, 2026-07-31, 2026-06-30,
  2026-05-31, 2026-04-30.

No entry is dated the first of the following month.

## Loading-shell scan

```
near-blank seconds: 32 80 102 120
4 of 64 samples
```

Four scene seams, none longer than one sample. The rejected take scored 13 of
63 with a nine-second stretch.

## Rejected take

The first retake (tenant `Tutorial Retake 36`, 124.12s) was rejected on its
**closing scene**, which the size-based scan did not catch: the Journal
Vouchers page held for its whole six seconds with the filters set, an empty
content area and Apply greyed out — the last of three filter fetches was still
in flight when the hold began, and the organisation switcher was still showing
`—`. The scene now sets the document type only and waits for the network to go
idle, one round trip instead of three.

## Finding, not a defect

The scene titled *"A short first period and a rounding row"* narrates a
contract that starts mid month opening and closing short. Every seeded contract
runs 1 January to 31 December, so the schedule on screen has twelve whole
months and no short period. The rounding row is real — the December row is
7,219.17 against 7,219.18 elsewhere, and the schedule still sums to the
contract rent exactly — but the short-period half of that sentence has nothing
to point at. Either the seed needs one mid-month contract or the narration
should drop the claim.
