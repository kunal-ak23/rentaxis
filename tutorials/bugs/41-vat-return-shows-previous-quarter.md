# 41: the VAT return shows the previous quarter's figures after a quick quarter switch

**Tutorial blocked:** 41 (VAT per instalment and the VAT return).

**Found:** 2026-09-29, recording stack (:3004/:8084, built from c5532cf), org Palm Ridge Properties,
as the Accountant. Page: Accounting → Final Reports → VAT return (`/dashboard/finance/reports/vat-return`).

## What happens
The page opens on the latest filed quarter (01/04/2026, filed as FTA-DEMO-2026Q2) and loads it. If the
user picks another quarter in "Quarter from" before that first load has returned, the selector shows the
new quarter but the boxes, the status and the check show the **first** quarter's return:

| Delay before choosing 01/07/2026 | Selector | Status shown | Box 9 shown |
|---|---|---|---|
| 0 ms | 01/07/2026 | Filed · FTA-DEMO-2026Q2 (April quarter) | 16,200.00 / 810.00 (April quarter) |
| 300 ms | 01/07/2026 | Not filed | 5,600.00 / 280.00 (correct) |
| 800 ms | 01/07/2026 | Not filed | 5,600.00 / 280.00 (correct) |

It stays wrong: nothing re-fetches, so a user can read (or export the PDF/CSV of) one quarter's figures
under another quarter's label. The page applies whichever response arrives last instead of the one for
the quarter now selected (no request cancellation or quarter check on the response).

## Expected
The figures always belong to the quarter in the selector: ignore or abort a response for a quarter that
is no longer selected.

## Reproduce
`tutorials/work/acct/e41d.mjs` (local explore script): open the page, choose 01/07/2026 immediately,
wait 4 s, read `vat-status` and `vat-box-9`. Snapshot `palmridge_base` has the data.
