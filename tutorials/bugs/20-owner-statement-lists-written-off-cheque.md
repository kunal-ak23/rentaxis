# 20: the Owner Statement still lists a settled, written-off returned cheque as overdue

**Tutorial blocked:** 20 (Financial reports: profit and loss, balance sheet, owner statement and aging).

**Found:** 2026-10-05, recording stack (:3004/:8084, built from e795c76 — main with #397 merged), org Palm Ridge
Properties, as the Accountant. Page: Accounting → Final Reports → Owner Statement
(`/dashboard/finance/reports/property-statement`), Palm Ridge Residences, August 2026.

## What happens
Section "4. Outstanding" shows **Overdue (register) (1) 22,500.00** with the row
`Daniel Brooks · R-104 · 440102 · 2025-12-01 · 22,500.00 · 268 days overdue`, beside
"Rent receivable (ledger) 0.00".

440102 was returned (CBR), the contract terminated and settled (STL-26/1), the balance written off (BDW-26/1)
and part-recovered (BDR-26/1). #397 took it off Due, Returned / replace and the Bounced tile (verified the same
day), but the Owner Statement's Outstanding section still applies the old rule, so an owner is told 22,500 is
overdue on a debt that was settled and written off.

## Expected
The same ledger-settled rule as #397: a returned cheque whose debt the ledger no longer carries is not
"overdue (register)" on the Owner Statement (its PDF/CSV too).

## Reproduce
Snapshot `pre40` (Palm Ridge base on the current schema); open the Owner Statement, Palm Ridge Residences,
month 2026-08, Apply; read section 4. Local explore script: `tutorials/work/acct/e20z.mjs`.
