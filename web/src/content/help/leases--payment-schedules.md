---
title: Payment Schedules
description: How payment schedules work and how to manage them
category: leases
roles: [TENANT_ADMIN, PROPERTY_MANAGER]
order: 3
---

## Payment Schedules

Each contract has an associated payment schedule that tracks all expected rent payments.

### Auto-Generated Schedule

When a contract is created, RentAxis automatically generates monthly payment entries:
- The first month is pro-rated if the contract doesn't start on the 1st
- Each entry includes: due date, amount, payment method, and status

### Payment Statuses

| Status | Meaning |
|--------|---------|
| **Pending** | Payment is expected but not yet due or collected |
| **Collected** | Payment has been received |
| **Deposited** | Payment (cheque) has been deposited at the bank |
| **Cleared** | Payment has fully cleared |
| **Overdue** | Payment is past due and not collected |
| **Bounced** | Cheque was returned/bounced |
| **Rejected** | Payment was rejected |

### Managing Payments

1. Go to **Cheque / Cash Collection** on the left rail
2. Pick a status pill — **To deposit**, **Due**, **Overdue**, **Returned / replace**, **Post-dated** or **Penalties** — or open the **Cheque register** for every cheque; each pill shows how many are waiting
3. Narrow any view to one property, or search a cheque number, tenant or unit (the search opens the register)
4. When a cheque bounces, the same dialog lets you replace it and charge the bounce fee
