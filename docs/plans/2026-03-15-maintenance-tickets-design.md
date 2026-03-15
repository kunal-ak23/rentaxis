# Maintenance Ticket System — Design

**Date:** 2026-03-15
**Status:** Approved

## Tables
- `maintenance_tickets` — id, tenant_id, property_id, unit_id, lease_id, reported_by, assigned_to, title, description, category, priority, status, estimated_resolution_hours, resolved_at, closed_at, closure_otp, satisfaction_rating, satisfaction_comment, created_at, updated_at
- `ticket_replies` — id, tenant_id, ticket_id, user_id, user_name, message, created_at
- `ticket_attachments` — id, tenant_id, ticket_id, file_url, file_type, file_size, uploaded_at

## Status Flow
OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED (with OTP for renter tickets)
RESOLVED → REOPENED → back to OPEN

## OTP Close Mechanism
- When ticket status becomes RESOLVED, system generates a 6-digit OTP stored on the ticket
- Renter sees OTP on their ticket detail page
- PM/admin must enter OTP to close renter-raised tickets
- Admin-raised tickets can be closed directly without OTP

## Access: PM can close tickets. Renters can create, view own, rate, and see OTP.
