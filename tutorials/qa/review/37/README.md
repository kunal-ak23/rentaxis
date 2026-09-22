# Tutorial 37 review

- Tutorial: Tenant ledger
- Environment: local stack (backend `:8081` rebuilt from HEAD, web `:3001`),
  synthetic tenant `Tutorial Retake 37` on a freshly recreated `rentaxis_v2`
- Narration: Azure Speech `en-US-Harper:MAI-Voice-2`, style `hopeful`, 125 wpm
- Final video: `tutorials/output/37-tenant-ledger.mp4`
- Subtitles: `tutorials/output/37-tenant-ledger.srt`
- Duration: 117.96 seconds; 15,044,118 bytes; 1920x1080; 29 subtitle cues
- Machine load average at capture: 12.87

## Proof before the capture

`web/e2e/finance/accounting-v2.spec.ts --project=tenant-admin` against the same
stack: **13 passed (56.6s)**, including *the renter ledger carries the clearing
and the return*. The whole file is run rather than one `-g` case because the
spec is `describe.configure({ mode: 'serial' })` and each test reads state the
earlier ones set.

## Frame checklist

| Frame | 1920x1080 | Rendered | No error | English | No renter phone / email / bank a/c | Subtitle matches |
|---|---|---|---|---|---|---|
| 5s | yes | ledger opened on the prepared renter, advance rent block | yes | yes | yes | "Open Finance and choose Tenant Ledger, then select the prepared renter." |
| 40s | yes | rent receivable, PDC receivable, the CBR return and both replacements | yes | yes | yes | "then one post-dated cheque entry per cheque credited it" |
| 80s | yes | post-dated cheques and advance rent blocks together | yes | yes | yes | "The contract credited the whole year, and each month-end recognition debits back" |
| 100s | yes | journal `TCO-26/2`, four lines, total 78,000.00 = 78,000.00 | yes | yes | yes | "cannot be edited or deleted, only reversed, which" |
| 115s | yes | trial balance, Grand Total 1,033,156.14 both sides | yes | yes | yes | "behind every screen in this tutorial is sound." |

Result: **approved.**

## Loading-shell scan

Sampling every 2 seconds and flagging frames under 60 KB (a near-blank PNG is
the app's spinner):

```
near-blank seconds: 30 32 72
3 of 59 samples
```

Two ordinary scene seams. The rejected take of this same scenario scored 10 of
61 with a six-second stretch; the difference is machine load, not the scenario
— see the Retakes section of the task report.

## Notes

- The renter's name appears (it is what the ledger is of); the tenant-ledger
  page prints no email or phone, so nothing needed hiding here.
- `reverse-journal` is deliberately not waited for: Reverse is MANUAL-only
  (`journals/__tests__/reverse-manual-only.test.tsx`) and this scene opens a
  LEASE-sourced TCO. The scene holds on the journal's balancing lines instead,
  which is what the narration is describing.
