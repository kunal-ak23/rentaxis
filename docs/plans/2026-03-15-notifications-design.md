# Notification & Alerts System — Design

**Date:** 2026-03-15
**Status:** Approved

## Architecture
Event-driven + daily cron job. Three channels: in-app (DB + polling), email (Azure Communication Services), push (FCM for Flutter).

## Tables
- `notifications` — id, tenant_id, user_id, type, title, message, reference_type, reference_id, channel, is_read, sent_at, created_at
- `device_tokens` — id, user_id, token, platform, created_at
- Add `payment_reminder_days` to rent_collection_settings

## Notification Types
- RENTER: PAYMENT_DUE, PAYMENT_OVERDUE, PAYMENT_CLEARED, LEASE_CREATED, CONTRACT_READY, TICKET_STATUS_CHANGED, TICKET_REPLY, TICKET_RESOLVED
- PM: TICKET_ASSIGNED, TICKET_REPLY, TICKET_OVERDUE, PAYMENT_BOUNCED, LEASE_EXPIRING, PAYMENT_OVERDUE_SUMMARY
- ADMIN: All PM notifications + MONTHLY_SUMMARY, LOW_SATISFACTION
- SUPER_ADMIN: TENANT_PROVISIONED

## Channels
- In-App: always, DB + polling
- Email: Azure Communication Services, high/medium priority
- Push: FCM, high priority, ready for Flutter

## Triggering
- Event-driven: immediate from service methods
- Scheduled: daily cron at 8 AM for reminders/overdue/expiry

## Frontend
- Notification bell in header with unread count
- Dropdown with recent notifications
- Full notifications page with filters

## API
- GET /api/v1/notifications (paginated)
- GET /api/v1/notifications/unread-count
- PUT /api/v1/notifications/{id}/read
- PUT /api/v1/notifications/read-all
- POST /api/v1/devices/register
