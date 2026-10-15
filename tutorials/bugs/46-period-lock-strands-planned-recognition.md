# 46: moving the period lock forward strands unposted (PLANNED) month-end recognition

**Tutorial blocked:** 46 (Period lock and year-end close).

**Found:** 2026-10-05, recording stack (:3004/:8084, built from e795c76 — main with #395/#397), org Palm Ridge
Properties, as the Accountant. Page: Accounting → Year End Closing (`/dashboard/finance/fiscal`), snapshot
`base1005` (books locked through 30/06/2026, recognition posted to 31/08/2026, September PLANNED).

## What happens
Lock through **30/09/2026** → confirm. The lock moves to 30/09/2026 with no warning, although 9
`recognition_entries` for September (period_end 2026-09-30) are still `PLANNED`:

    select status, count(*) from recognition_entries
     where tenant_id = '<Palm Ridge>' and period_end <= '2026-09-30' group by status;
    -- PLANNED | 9   (plus POSTED 93, CANCELLED 11)

`TenantFiscalSettingsService.lockThrough` refuses only for a PLANNED VAT tax point
(`requireNoPlannedVatThrough`); there is no equivalent check for PLANNED recognition (the
`recognitionEntries` guard covers only the books-start lock). Once locked, September's income can no
longer be recognised: the nightly job skips locked dates and a manual run would post into a locked
period. Advance Rent stays overstated and Rental Income understated for September with nothing on
screen saying so.

The year-end close preview already lists the same condition as a blocker ("28 recognition period(s)
ending on or before 31/12/2026 are not posted yet; run month-end recognition to year end first"), so the
two lock paths disagree.

Also seen: after the lock moves, the sidebar footer still says "Books locked through 30/06/2026" until a
full reload.

## Expected
`lockThrough` refuses, like the VAT case: "N recognition period(s) ending on or before <date> are not
posted yet; run month-end recognition through <date> first." (The tutorial narration already says this.)

## Reproduce
`tutorials/recording-stack.sh restore base1005`; sign in as the Palm Ridge Accountant; Year End Closing →
Lock through 30/09/2026 → Lock through. Local explore script: `tutorials/work/acct/x15/k46b.mjs`.
Restore `base1005` afterwards.

## Resolved
Fixed by PR #400 (merged, deployed): `lockThrough` refuses with "Run month-end recognition through <date> first: N recognition period(s) … are not posted yet (MM/YYYY)" and links to Month-end recognition; the sidebar lock date refreshes. Tutorial 46 recorded on 08de9c87 (YouTube ex4na1Mp-EU).
