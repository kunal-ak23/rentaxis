# Meetings Module — Design Document

**Date:** 2026-04-09
**Status:** Approved
**Feature:** Premium — Meetings & Scheduling

## Overview

The Meetings module enables renters and property managers to schedule two types of meetings:

1. **Office Visit** — renter visits the office for cheque replacement, lease renewal, or custom reasons
2. **Property Visit** — prospect/renter schedules a visit to view a property

Either side (renter or PM/admin) can create either type of meeting. The system provides an Outlook-style calendar experience with conflict detection and automatic slot suggestions.

## Key Decisions

- **Single `meetings` table** with `type` discriminator + `meeting_details` child table for purpose-specific data
- **Fixed 30-minute slots** from 9:00 AM to 9:00 PM (24 slots/day)
- **Conflict check against the host PM's schedule** — not the property
- **Approval flow:** REQUESTED → APPROVED → CONFIRMED → COMPLETED / CANCELLED / NO_SHOW
- **Detailed prep info:** cheque replacement captures specific cheques from payment schedule; lease renewal captures proposed terms
- **Notifications:** in-app + email on all status transitions
- **Calendar UI:** full calendar (month/week/day) on web; simplified week/agenda view on mobile

## Data Model

### `meetings` table

| Column | Type | Notes |
|--------|------|-------|
| id | UUID PK | gen_random_uuid() |
| tenant_id | UUID NOT NULL | FK → tenants |
| type | VARCHAR NOT NULL | `OFFICE_VISIT`, `PROPERTY_VISIT` |
| status | VARCHAR NOT NULL | `REQUESTED`, `APPROVED`, `CONFIRMED`, `COMPLETED`, `CANCELLED`, `NO_SHOW` |
| purpose | VARCHAR NOT NULL | `CHEQUE_REPLACEMENT`, `LEASE_RENEWAL`, `PROPERTY_VIEWING`, `OTHER` |
| title | VARCHAR | Auto-generated or custom |
| notes | TEXT | Free-text from requester |
| slot_start | TIMESTAMP NOT NULL | Start of 30-min slot |
| slot_end | TIMESTAMP NOT NULL | End of 30-min slot |
| host_user_id | UUID NOT NULL | FK → users (PM being booked) |
| requester_user_id | UUID NOT NULL | FK → users (who created it) |
| lease_id | UUID | FK → leases (for office visits) |
| property_id | UUID | FK → properties (for property visits) |
| unit_id | UUID | FK → units (for property visits) |
| version | BIGINT | Optimistic locking |
| created_at | TIMESTAMP | auto |
| updated_at | TIMESTAMP | auto |

### `meeting_details` table

| Column | Type | Notes |
|--------|------|-------|
| id | UUID PK | gen_random_uuid() |
| meeting_id | UUID NOT NULL | FK → meetings (unique) |
| detail_type | VARCHAR NOT NULL | `CHEQUE_REPLACEMENT`, `LEASE_RENEWAL` |
| payment_schedule_ids | UUID[] | Cheques being replaced |
| proposed_start_date | DATE | For lease renewal |
| proposed_end_date | DATE | For lease renewal |
| proposed_rent_amount | DECIMAL(15,2) | For lease renewal |
| notes | TEXT | Additional detail |

### Indexes

- `meetings`: tenant_id, (host_user_id, slot_start) for conflict checks, status, requester_user_id, lease_id, property_id
- `meeting_details`: meeting_id (unique)

## API Endpoints

`/api/v1/meetings`

| Method | Path | Roles | Description |
|--------|------|-------|-------------|
| POST | `/` | Any authenticated | Create meeting (REQUESTED) |
| GET | `/` | PM, Admin | Paginated list with filters (date, status, type, host) |
| GET | `/my` | Renter | Paginated list of my meetings |
| GET | `/{id}` | Involved parties | Meeting detail with meeting_details |
| PUT | `/{id}/approve` | PM, Admin | Approve → sets APPROVED |
| PUT | `/{id}/cancel` | Involved parties | Cancel meeting |
| PUT | `/{id}/complete` | PM, Admin | Mark completed |
| PUT | `/{id}/no-show` | PM, Admin | Mark no-show |
| GET | `/slots` | Any authenticated | Available slots for host + date |
| GET | `/calendar` | PM, Admin | Paginated calendar feed for date range |

### Conflict handling

- On `POST /`, if the requested slot is already booked for the host PM, return `409 Conflict` with the next available slot in the response body.
- `GET /slots?hostUserId=X&date=YYYY-MM-DD` returns the list of free 30-min slots (9:00–21:00 minus booked).

## Frontend — Web Dashboard

### Pages

1. **`/dashboard/meetings`** — Main page with toggle between:
   - **Calendar view** (default): month/week/day via `@fullcalendar/react`, meetings color-coded by type/status
   - **Table view**: paginated table with filters (date range, type, status, host)

2. **`/dashboard/meetings/[id]`** — Detail page:
   - Meeting info, linked lease/property, meeting details (cheque/renewal info)
   - Status action buttons based on role
   - Status history timeline

3. **Create Meeting modal** (multi-step):
   - Step 1: Select type (Office Visit / Property Visit)
   - Step 2: Select purpose → pick lease or property/unit
   - Step 3: Pick date → shows available slots
   - Step 4: Purpose-specific details (cheques or renewal terms)
   - Step 5: Notes → Submit

### Renter Portal (`/renter/meetings`)
- Paginated chronological list with status badges
- Create meeting flow (same steps, adapted for renter context)

## Mobile Apps

### Manager App
- Week/agenda view for upcoming meetings
- Meeting detail with action buttons (Approve/Cancel/Complete/No-Show)
- Push notifications on status changes

### Renter App
- Chronological list of my meetings
- Request meeting flow (bottom sheets, date picker for slots)
- Push notifications on status changes

## Notifications

Using existing `NotificationService` + `Notification` entity:

| Event | In-App To | Email To |
|-------|-----------|----------|
| Meeting requested | Host PM | Host PM |
| Meeting approved | Requester | Requester |
| Meeting cancelled | All parties | All parties |
| Meeting completed | Requester | Requester |
| No-show recorded | Requester | Requester |
