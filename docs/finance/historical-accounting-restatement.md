# Historical accounting mis-statement — scope and options

Status: **open decision for the business owner.** No historical data has been
changed. Every fix referenced here is forward-only: postings made from the fix
date are correct, postings made before it are not.

Written 2026-09-09 alongside the fixes for #184 and #190. Update this file when
a restatement decision is made.

## 1. Security deposits recognised as rental income (#184)

### What was wrong

`PaymentScheduleService.clearPayment` resolved `RENT_PAYMENT_CLEARED` for every
schedule row, including rows flagged `is_security_deposit`. So when a deposit
cheque cleared, the credit leg went to **C-01-01 Rental Income** instead of
**B-01-02 Security Deposits**.

Settlement posted nothing to the ledger at all, so the refund never reversed it
either. The `SECURITY_DEPOSIT_RECEIVED` and `SECURITY_DEPOSIT_REFUNDED` mappings
were seeded and editable in the admin UI the whole time — nothing ever read them.

### Effect on the books, per tenant

- **Income overstated** by the total of all cleared deposit rows, for every
  period since the tenant started clearing deposits. In the UAE the deposit is
  typically one month's rent, so the overstatement is roughly one month's rent
  per lease that has ever had its deposit cheque cleared.
- **Liabilities understated** by the same amount: B-01-02 has been sitting at
  zero while the landlord genuinely owes that money back.
- Every P&L, trial balance and portfolio report built on those accounts inherits
  both errors.

### Identifying the affected rows

Deposit postings are identifiable because the schedule row carries the flag:

```sql
-- Cleared deposit rows, per tenant, with what they should have credited
SELECT ps.tenant_id,
       count(*)          AS deposit_rows,
       sum(ps.amount)    AS total_mis_posted
FROM   payment_schedules ps
WHERE  ps.is_security_deposit = true
AND    ps.status = 'CLEARED'
GROUP  BY ps.tenant_id
ORDER  BY total_mis_posted DESC;
```

The matching ledger rows are the `C-01-01` credit legs whose description begins
`Rental income - Lease installment #`. Note the description alone is not a safe
key — ordinary rent uses the same prefix. Join back through the schedule row.

### Options

1. **Do nothing.** Prior periods stay overstated. Acceptable only if no filed
   accounts or investor reporting depend on them.
2. **Reclassification journal, current period.** One dated entry per tenant:
   debit C-01-01, credit B-01-02, for the total of cleared-but-not-yet-refunded
   deposits. Leaves prior-period reports as filed and makes the balance sheet
   correct going forward. **This is the usual accounting answer.**
3. **Full restatement.** Rewrite the historical legs to B-01-02. Changes
   already-reported figures; only do this on an accountant's instruction.

A Liquibase changeset for option 2 has **not** been written — it needs the
cut-off date and the treatment of deposits already refunded, which are business
decisions.

## 2. VAT return reports VAT-inclusive gross as taxable sales (#188)

**Not yet fixed at the time of writing.** Recorded here so the two are assessed
together, since both affect figures that may already have been filed.

`getVatReturn` sets `taxableAmount` to `credit - debit`. For rent, VAT is
*inclusive*, so the credit leg is the gross — meaning taxable sales are reported
including the VAT. A 5,250 AED installment is reported as 5,250 taxable with 250
VAT, instead of 5,000 with 250.

Manual split transactions use the opposite (additive) convention and are
correct, so a single VAT return can mix both bases. Any VAT201 filed from these
figures reports a taxable base that does not reconcile to the output VAT beside
it.

The stored `net_amount` on the credit leg already holds the correct figure for
rows posted after VAT support landed, which is what the fix should use. Rows
predating it have no `net_amount` and would need recomputation from `vat_amount`.

### Before filing again

Re-run the VAT return for any period after the fix ships and compare it against
what was filed. If they differ, that is the correction to disclose — the FTA
cares more about a voluntary disclosure than a mismatch found later.

## What has been fixed forward

| Issue | Fix | Shipped |
|---|---|---|
| #184 | Deposits credit B-01-02 on clear; settlement releases the liability and routes retained deductions to C-01-02 | see PR |
| #190 | Ledger rejects client-supplied ids; `created_at`/`updated_at` now written | PR #221 |
| #183 | Cheque clear resolves a bank account that exists | PR #223 |
| #185 | Arrears stay visible after the nightly job relabels them | PR #221, #222 |
| #186 | Settlement stops deducting already-paid penalties | PR #221 |

Still open and material to the books: **#187** (nothing enforces double-entry —
manual transactions post a single one-sided row, so the trial balance can never
be used to detect an error) and **#128** (no audit trail on lease amount or role
changes).
