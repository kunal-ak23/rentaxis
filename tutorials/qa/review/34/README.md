# Tutorial 34 review

- Tutorial: Post a tenancy contract
- Environment: local stack (backend `:8081`, web `:3001`), synthetic tenant
  `Tutorial Demo 2026-09-22 C`, seeded fresh by `scripts/seed_demo_tenant.py`
  so the contract this tutorial posts was still a DRAFT when the take started.
- Narration: Azure Speech `en-US-Harper:MAI-Voice-2`, style `hopeful`, 125 wpm
- Final video: `tutorials/output/34-post-a-tenancy-contract.mp4`
- Subtitles: `tutorials/output/34-post-a-tenancy-contract.srt`
- Duration: 129.40 seconds; 11,713,902 bytes; 1920x1080; 35 subtitle cues

## Proof before the capture

`web/e2e/finance/accounting-v2.spec.ts --project=tenant-admin` against the same
local stack: **13 passed (24.8s)**, which includes *the draft contract takes a
cheque grid and posts as one TCO plus one PDR per cheque* — the posting flow
this tutorial records. The whole file is run rather than one `-g` case because
the spec is `describe.configure({ mode: 'serial' })` and each test reads state
the earlier ones set; grepping a single case runs it against unset state.

Scenario dry run (`TUTORIAL_CAPTURE_VALIDATE_ONLY=1`) on a separate throwaway
tenant: `scenario_validation=passed`, all six scenes.

## Frame checklist

| Frame | 1920x1080 | Rendered | No error | English | No renter phone / bank a/c | Subtitle matches |
|---|---|---|---|---|---|---|
| 5s | yes | draft contract, charge lines and cheque grid | yes | yes | yes | "…draft tenancy contract into accounting entries." |
| 40s | yes | cheque grid, "Cheques match the contract value of 140,000.00" | yes | yes | yes | "The second grid is the cheque register for this contract." |
| 80s | yes | draft still editable, Post Contract offered | yes | yes | yes | "a draft can be edited freely." |
| 120s | yes | Journals tab: one TCO-26/4 and five PDR entries | yes | yes | yes | "Posting is deliberate: after a contract is posted it is never edited," |
| 128s | yes | Recognition schedule, 12 planned periods, Σ 110,000.00 | yes | yes | yes | "only amended, which reverses the original and writes a fresh one." |

Result: **approved.**

## Notes

- The renter card on the lease overview prints the renter's email and phone
  beside the charge lines, and the page fits a 1080-tall frame whole, so there
  is nowhere to scroll them out of shot. `record-tutorial.mjs` hides those two
  rows for tutorials 34–37 (`CAPTURE_STYLE_RULES`). The renter's name stays —
  it is what names the contract. The first take was rejected for exactly this:
  frames sampled while the scene was still clicking showed both.
- Sara's draft carries two charge lines, a security deposit and rent. The
  storyboard narration names an administration fee as a third; only Ahmed's
  contract is seeded with one, and his is already posted. The spoken line reads
  as the general rule rather than a caption of this screen.
