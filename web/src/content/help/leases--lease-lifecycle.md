---
title: Lease Lifecycle
description: Understanding lease statuses and transitions
category: leases
roles: [TENANT_ADMIN, PROPERTY_MANAGER]
order: 2
---

## Tenancy Contract Lifecycle

Every contract in RentAxis follows a defined lifecycle.

### Status Flow

DRAFT → PENDING_SIGNATURE → ACTIVE → NOTICE_GIVEN → TERMINATED/EXPIRED → CLOSED

| Status | Meaning |
|--------|---------|
| **Draft** | Tenancy Contract is being prepared. Can still be edited freely. |
| **Pending Signature** | Sent to tenant for review/acceptance. |
| **Active** | Tenancy Contract is live. Unit is marked occupied. Payments are expected. |
| **Notice Given** | Either party has given notice to end the contract. |
| **Terminated** | Tenancy Contract was ended before its natural expiry date. |
| **Expired** | Tenancy Contract reached its end date. |
| **Closed** | All financial obligations settled. Tenancy Contract is archived. |

### Key Actions

The buttons at the top of a contract show the next step for its status: **Edit** and **Post Contract** for a draft; **Record payment** and **Renew** for an active contract (or one under notice); **Settlement** once it has ended. Everything else is under **More actions**: Extend Contract, Amend lines, Add charge, Transfer, Assignment, Reduce, Raise penalty, Give notice, Terminate, Write off, Download contract, Ledger, and Delete for a draft. Which actions you see depends on your role.

- **Post Contract** — Checks the contract (a dry run), then writes its journal; this is how a draft becomes active
- **Give notice** — Records that notice has been given, with a notice date
- **Terminate** — Prices the move-out on its own page, then ends the contract
- **Settlement** — Settles the deposit and remaining balances once the contract has ended

### The Contract Page

A contract has four tabs:

- **General** — the contract and tenant details, the Particulars and any rent-free periods
- **Cheques** — the cheque grid, then Penalties, Journal Vouchers, Revenue recognition and, when the contract charges VAT, Vat — each a section you can open or close
- **Attachments** — the contract document, supporting documents, and Addenda & Ejari
- **Activities** — Notes and Maintenance

A link to one of the old tabs (for example "?tab=journals") opens the tab that holds it now, with that section open.
