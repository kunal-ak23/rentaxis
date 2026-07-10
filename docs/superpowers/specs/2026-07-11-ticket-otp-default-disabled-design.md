# Disable Ticket-Close OTP by Default — Design

**Date:** 2026-07-11
**Status:** Approved (design)
**Author:** Kunal Sharma (with Claude)

## Problem

Closing a resolved maintenance ticket requires a property manager/admin to enter an OTP that the renter shares, gated by the per-tenant `LandlordOrg.ticketOtpRequired` flag. This flag currently defaults to `true`, requiring every tenant to go through OTP verification unless a superadmin manually disables it for them. The requirement should default to off, with tenants able to opt in via the existing superadmin toggle.

## Goals

- New tenants (`LandlordOrg` rows created going forward) get `ticket_otp_required = false` by default.
- Existing tenants are flipped to `ticket_otp_required = false` as part of this change.
- A tenant can still have OTP-gated ticket closing re-enabled.

## Non-goals

- No change to who controls the toggle — stays `SUPER_ADMIN`-only via the existing `PUT /api/admin/tenants/{id}` endpoint and superadmin UI (`web/src/app/[locale]/superadmin/tenants/page.tsx`). No tenant-admin self-service toggle.
- No change to the OTP generation/validation logic in `MaintenanceTicketService` (`updateStatus`, `closeWithOtp`) — it already correctly reads `ticketOtpRequired` and skips validation when false.
- No change to ticket close permissions (still property manager/tenant admin/super admin only).

## Decisions (from brainstorming)

| Question | Decision |
|---|---|
| Toggle access | Keep `SUPER_ADMIN`-only, unchanged. |
| Existing tenants | Flip all existing `LandlordOrg` rows to `ticket_otp_required = false`, not just new ones. |

## Design

This is a migration-only change — the OTP flow, toggle mechanism, service logic (`MaintenanceTicketService.closeWithOtp`, `LandlordOrg.java:139-140`), and superadmin UI already exist and already respect the flag correctly.

- New Liquibase changeset `65-ticket-otp-default-disabled.yaml`:
  1. Alter `landlord_org.ticket_otp_required` column default to `false`.
  2. `UPDATE landlord_org SET ticket_otp_required = false` — applies to all existing rows regardless of current value.
- No application code changes.

### Testing

- Liquibase changeset applies cleanly against an existing dev database with pre-existing `LandlordOrg` rows (both `true` and `false` values) and results in all rows reading `false`.
- Manual verification: close a `RESOLVED` ticket for a tenant without supplying an OTP and confirm it succeeds (existing `closeWithOtp` behavior when the flag is false — no new test needed, just confirms the migration took effect).

## Open questions / risks

- None outstanding — scope and constraints were confirmed during brainstorming.
