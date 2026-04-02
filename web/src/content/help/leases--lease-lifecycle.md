---
title: Lease Lifecycle
description: Understanding lease statuses and transitions
category: leases
roles: [TENANT_ADMIN, PROPERTY_MANAGER]
order: 2
---

## Lease Lifecycle

Every lease in RentAxis follows a defined lifecycle.

### Status Flow

DRAFT → PENDING_SIGNATURE → ACTIVE → NOTICE_GIVEN → TERMINATED/EXPIRED → CLOSED

| Status | Meaning |
|--------|---------|
| **Draft** | Lease is being prepared. Can still be edited freely. |
| **Pending Signature** | Sent to renter for review/acceptance. |
| **Active** | Lease is live. Unit is marked occupied. Payments are expected. |
| **Notice Given** | Either party has given notice to end the lease. |
| **Terminated** | Lease was ended before its natural expiry date. |
| **Expired** | Lease reached its end date. |
| **Closed** | All financial obligations settled. Lease is archived. |

### Key Actions

- **Activate** — Move from Draft to Active (requires all fields complete)
- **Give Notice** — Mark that notice has been given with a notice date
- **Terminate** — End the lease early
- **Close** — Settle remaining balances and archive
