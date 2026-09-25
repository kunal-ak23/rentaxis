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

- **Activate** — Move from Draft to Active (requires all fields complete)
- **Give Notice** — Mark that notice has been given with a notice date
- **Terminate** — End the contract early
- **Close** — Settle remaining balances and archive
