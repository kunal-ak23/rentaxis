# Extension tax-invoice backfill (sim r24 S24-03)

Before #425, extending a lease on CONTRACT VAT timing credited Output VAT on the extension's
TCO ("Extension to yyyy-mm-dd") but recorded no tax point and issued no tax invoice. #425 fixed
this for new extensions only. The older ones leave the VAT return's documents short of the
Output VAT ledger (T7c / the J07 documents-vs-ledger check).

## 1. Preview (read only)

```
zsh .superpowers/agents/run-prod-check.sh s2403-predeploy
```

`prod-check-s2403-predeploy.sql` runs in `BEGIN TRANSACTION READ ONLY … ROLLBACK`. It lists every
undocumented extension TCO for each organisation. Each row shows its VAT, its date, and
`in_filed_period`. It also gives totals (to issue / to disclose) and the organisations with no TRN.

## 2. Backfill per organisation (admin, idempotent)

`POST /api/v1/finance/vat/extension-invoices/backfill?dryRun=true` is the default and writes
nothing. `dryRun=false` issues the invoices.

- **Who:** SUPER_ADMIN (with the organisation selected), TENANT_ADMIN or ACCOUNTANT.
- **Scope:** the caller's organisation only.
- **Candidate:** an extension TCO on a CONTRACT-timing lease that moves Output VAT and has no
  tax point.
- **Issue:** each candidate gets a POSTED CHARGE tax point and a tax invoice, dated on the
  TCO's own date (the extension's tax point). One transaction per extension, under the lease
  lock. Nothing is posted: the VAT is already in the ledger.
- **Second run:** issues nothing.

Outcomes per row:

| outcome | meaning |
|---|---|
| `WOULD_ISSUE` / `ISSUED` | invoice (to be) issued; `invoiceNumber` names it |
| `FILED_PERIOD` | the date is inside a FILED VAT return: **not issued** (ruling R11, a filed return's documents never change). Disclose the VAT on the next return. |
| `NO_TRN` | the organisation has no TRN; add it and run again |
| `LINES_DIFFER` | the extension's lines no longer carry the TCO's VAT (lines rebuilt since); review by hand |
| `REVERSED` | the TCO was reversed; nothing to document |
| `LOCKED` / `FAILED` | retry, or see `note` |

## 3. After

Run the VAT return's output check for the open quarter: the documents-vs-ledger difference
should be gone. For each `FILED_PERIOD` row, record a disclosure on the next return: the
extension's entry number, date and VAT.
