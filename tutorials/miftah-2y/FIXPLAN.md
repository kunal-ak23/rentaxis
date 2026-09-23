# Miftah — fix plan from the two-year simulation

31 findings, ordered by what actually blocks a landlord. Each row names the change, not just
the symptom.

## Wave 1 — the product cannot do the job without these

| # | Fix | Where |
|---|---|---|
| 31 | **Generate expense leaves in the property-account template.** `D-01 Direct Expense` already promises "one leaf per property per category" — build the categories (repairs, cleaning, security, chiller/DEWA, insurance, service charge, management) the way income leaves are already generated. Without it, AP and every expense report are dead on a fresh tenant. | `AccountService` seed / property-account template |
| 15 + 17 | **Allow a posted contract to be varied.** Two parts: (a) let an amendment *raise* the contract value, not just redistribute it; (b) let the cheque grid take a new row on a posted lease so the extra value can be collected. The clean shape is an *addendum* document — a second TCO for the delta plus its own PDR — which preserves immutability and gives mid-term parking, storage and chiller recharges a path. | `LeaseService.amendLeaseLines`, lease detail cheque grid |
| 12 | **Let a human raise a penalty.** `POST /api/v1/penalties` already exists and is authorised for `PROPERTY_MANAGER`+. Add a "Propose penalty" action on `/finance/penalties` (and on the lease) with amount, reason and date. | `/dashboard/finance/penalties` |
| 8 | **Build the renter detail page.** `/dashboard/renters/[id]` with leases, ledger, documents, contact history and a credential reset. Today "View" is a `<span>` with no handler. | `web/src/app/[locale]/dashboard/renters` |
| 3 | **Add `ACCOUNTANT` to the role dropdown.** The enum has it and accounting v2 gates journals, opening balances, period lock and penalty approval on it. One-line-ish fix with a real permissions consequence. | superadmin New User form |
| 9 | **Refuse to bank a post-dated cheque before its cheque date** — on the row action and on the batch dialog. | `ChequeService.deposit` / `depositBatch` |

## Wave 2 — wrong numbers or wrong words in the ledger

| # | Fix |
|---|---|
| 13 + 30 | Stop routing penalties and settlement balances through **PDC Receivable**. They have no instrument; they belong in ordinary receivables. Today Rent Receivable reports nil while money is owed, and PDC Receivable cannot be reconciled to a cheque book. |
| 21 | Render every timestamp in the tenant's timezone. The same instant currently shows as 22/09 on the lease header and 23/09 in the ticket history. |
| 26 | Fix the termination help text — returning a cheque does **not** stop the money being owed; it moves the debt back to receivables and the on-screen figure goes *up*. |
| 11 | Rename the Clear dialog's "Deposit Date" to "Clearing date" — it sets the value date of the bank entry. |
| 25 | Resolve the user id to a name on the finalized settlement statement. |
| 7 | Replace one-shot plaintext portal passwords with an invite / set-password link (the `/auth/set-password` route already exists), and give the tenant admin a reset action. |
| 2 | Same for staff provisioning: stop requiring an operator-invented password. |

## Wave 3 — real-world fidelity

| # | Fix |
|---|---|
| 24 | Rent escalation on renewal: a % uplift or new-rent field, ideally with the RERA index, plus prompt for the new Ejari number. |
| 23 | Mark charge types as recurring vs one-off so renewal stops copying the admin fee forward. |
| 27 | Record a notice **date**, and distinguish renter notice from landlord notice. |
| 18 | Let a ticket carry a reported date. |
| 19 | Make "on behalf of" a renter picker, not free text. |
| 5 | Put a period on every rent field (per year / per month). |
| 22 | Per-ticket "close without OTP, with reason" for unreachable tenants. |
| 10 | Per-cheque dates in batch deposit. |
| 16 | Disable the blocked AMEND LINES button, or say why it failed. |

## Wave 4 — polish

4 (Project/Property naming), 6 (unsorted units), 14 (`#7` instrument label), 20 (ticket
reference numbers), 28 (renter portal for non-renters), 29 (Arabic pagination), 1 (web still
branded RentAxis).
