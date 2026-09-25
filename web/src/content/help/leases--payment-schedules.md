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

1. Go to **Cheque / Cash Collection** from the left rail
2. View all payment schedules across contracts
3. Update payment status as you collect rent
4. For cheque payments, track the deposit and clearance process
