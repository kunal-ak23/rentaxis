# 40: a returned cheque the settlement and write-off closed is still listed as due

**Tutorial blocked:** 40 (Returned cheques: replace, settle in cash, write off and recover). Its lesson is
that approving a write-off "takes the items off every list of money due"; the Cheque / Cash Collection
screens contradict that on the seeded Palm Ridge data.

**Found:** 2026-09-29, recording stack (:3004/:8084, built from c5532cf), org Palm Ridge Properties
(`tutorials/seed/seed_palm_ridge.py`), as the Accountant.

## Data
Daniel Brooks, R-104 (Palm Ridge Residences): cheque 440102 (22,500, 1 Dec 2025) returned 5 Dec 2025
(CBR). Contract terminated 31 Jan 2026 (TCR), settlement STL-26/1 finalised 10 Feb 2026 against the
4,500 deposit with a balance due of 11,326.03 on a collection row, which was written off (BDW-26/1,
11,326.03, approved) and part-recovered (BDR-26/1, 5,000). Daniel's Rent Receivable is 0.00 in the
Tenant Ledger and the lease has no write-off candidates.

## What the screens show
| Screen | Shows |
|---|---|
| Cheque / Cash Collection → **Due** | 440102, Daniel Brooks, 22,500.00 (with Hana's live cheque) |
| **Returned / replace** (count 1) | 440102 with a **Replace** action |
| Cheque register summary tile **Bounced** | 22,500.00 (1) |
| **Overdue** | 440102 correctly left out ("1 overdue found in the first 2 of 2 due cheques") |
| Cheque register row | the "settled in the ledger" marker (`cheque-ledger-settled-*`) |
| `GET /api/v1/leases/{id}/cheques` | 440102 `due: true, overdue: true, daysOverdue: 297, ledgerSettled: false` |

So the register row and Overdue know the ledger no longer carries 440102 (F14-52, `ChequeQueryService
.ledgerSettled`), but **Due** (`ChequeQueryService.due` → `ChequeRepository.findDue`, a plain SQL
predicate), the Returned / replace queue, the Bounced tile and the lease cheque DTO do not apply that
rule. The "due" figures overstate what is owed by 22,500 for a debt that was settled and written off, and
the queue invites a replacement for it.

## Expected
A bounced row whose debt the ledger has closed (settlement, replacement or write-off) is not due, not
listed for Return / replace, not counted in the Bounced tile, and reports `ledgerSettled: true` on every
endpoint — the same derivation the register row and Overdue already use.

## Reproduce
1. Restore snapshot `palmridge_base` (`tutorials/recording-stack.sh restore palmridge_base`).
2. Sign in as accounts@palmridge.example (password in tutorials/work/seed/palm-ridge.env, local only).
3. Open Cheque / Cash Collection → Due, then Returned / replace, then Cheque register.
