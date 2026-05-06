# Renter Lifecycle Test — 2026-05-06

> **Status:** scaffold (boxes unchecked). Tester walks through this on production after the `feat/tokenized-invite-and-lifecycle` branch is deployed, ticks each `[ ]` as observed, and notes anomalies inline.

**Tenant:** Email QA Phase 1 (`bdcbe284-52dd-46f8-a63a-286022c12962`)
**Backend SHA at start of run:** _[fill in]_
**Run started:** _[timestamp]_
**Run completed:** _[timestamp]_
**Tester:** _[name]_

---

## Pre-conditions

- Tenant `Email QA Phase 1` exists with `EMAIL_NOTIFICATIONS=ON`.
- Admin user `kunal.sharma+emailqa@datagami.in` (password: `RentTest@2026!`) is the TENANT_ADMIN.
- Web app reachable at https://rentaxis.uaenorth.cloudapp.azure.com.
- Renter mobile app installed on a device with Gmail access for `kunalsharma.ks13@gmail.com`.
- All event emails routed via `+addressing` to a single Gmail inbox.

**Test users created during the run** (all `+addressed` so a single Gmail receives them):

| Role | Email | Purpose |
|---|---|---|
| RENTER | `kunalsharma.ks13+lifecycle-renter@gmail.com` | The user under test |
| PROPERTY_MANAGER | `kunalsharma.ks13+lifecycle-pm@gmail.com` | Receives ticket notifications |

---

## Phase A: Onboarding

- [ ] **A1.** Admin creates RENTER `kunalsharma.ks13+lifecycle-renter@gmail.com` via `POST /api/admin/users` (or admin UI).
  - **Expected event:** `USER_INVITED` to renter mailbox.
  - **Expected outbox row:** PENDING → SENT within 30s.
  - **Email subject:** `You're invited to Email QA Phase 1 on RentAxis`
  - **Email CTA:** `https://rentaxis.uaenorth.cloudapp.azure.com/en/auth/set-password?token=<64-hex>`
  - **Observed:** _[paste subject + outbox row id]_

- [ ] **A2.** Renter clicks CTA → web set-password page renders → renter sets password `RenterTest@2026!`.
  - **Expected:** redirect to `/auth/login?welcome=1`. Token cleared in DB.
  - **Expected event:** `PASSWORD_CHANGED` to renter mailbox.
  - **Observed:** _[outbox row + behavior]_

- [ ] **A3.** Renter logs in via web with new credentials.
  - **Expected event (first login):** `USER_WELCOMED` to renter mailbox.
  - **Observed:** _[outbox row]_

- [ ] **A4.** Renter installs renter mobile app, logs in.
  - **Expected:** in-app session starts; no duplicate `USER_WELCOMED` (`welcomed_at` already set).
  - **Observed:** _[behavior]_

---

## Phase B: Lease lifecycle

- [ ] **B1.** Admin creates a Property and a Unit.
  - **No emails expected.**

- [ ] **B2.** Admin creates a Lease assigning the Unit to the renter.
  - **Expected event:** `LEASE_CREATED` to renter.
  - **Observed:**

- [ ] **B3.** Admin generates the contract.
  - **Expected events:** `LEASE_CONTRACT_GENERATED` AND `LEASE_SIGNATURE_REQUESTED` to renter.
  - **Observed:**

- [ ] **B4.** Renter reviews and accepts the lease.
  - **Expected events:** `LEASE_SIGNED` AND `LEASE_ACTIVATED` to renter.
  - **Observed:**

---

## Phase C: Payments

- [ ] **C1.** Admin records a cheque (collected/received).
  - **Expected event:** `CHEQUE_RECEIVED` to renter.
  - **Observed:**

- [ ] **C2.** Admin marks cheque deposited.
  - **Expected event:** `CHEQUE_DEPOSITED` to renter.
  - **Observed:**

- [ ] **C3.** Admin marks cheque cleared (or bounced — pick one path; if bounced, follow C3a).
  - **Expected event:** `CHEQUE_CLEARED` (or `CHEQUE_BOUNCED`) to renter.
  - **Observed:**
  - **C3a.** If bounced: confirm `PENALTY_INCURRED` fires per business rule.

- [ ] **C4.** Renter completes an online (Razorpay test) payment.
  - **Expected event:** `ONLINE_PAYMENT_RECEIVED` to renter.
  - **Confirm `notify("PAYMENT_CLEARED",…)` no longer doubles up** (post PR #42 cleanup).
  - **Observed:**

- [ ] **C5.** Admin generates a rent receipt PDF.
  - **Expected event:** `RENT_RECEIPT_AVAILABLE` to renter, **with PDF attached** (post PR #42 attachment plumbing).
  - **Verify:** download the PDF from Gmail; opens cleanly.
  - **Observed:**

---

## Phase D: Tickets

- [ ] **D1.** Renter raises a maintenance ticket from the renter app.
  - **Expected event:** `TICKET_CREATED` to property manager mailbox.
  - **Observed:**

- [ ] **D2.** PM (admin acting as PM) assigns the ticket to themselves.
  - **Expected event:** `TICKET_ASSIGNED` to assignee.
  - **Observed:**

- [ ] **D3.** PM replies on the ticket.
  - **Expected event:** `TICKET_REPLY` to renter.
  - **Observed:**

- [ ] **D4.** PM marks ticket resolved.
  - **Expected event:** `TICKET_RESOLVED` to renter.
  - **Observed:**

- [ ] **D5.** Renter reopens the ticket.
  - **Expected event:** `TICKET_REOPENED` to PM.
  - **Observed:**

---

## Phase E: Wind-down

- [ ] **E1.** Admin terminates the lease.
  - **Expected event:** `LEASE_TERMINATED` to renter.
  - **Observed:**

---

## Phase F: XSS regression check

- [ ] **F1.** Admin creates a new property named exactly `Tower<a href="https://evil.example">click</a>`.
- [ ] **F2.** Trigger `LEASE_CREATED` for that property to a different renter.
  - **Expected:** body renders escaped — literal `<a href=…>` text, NO clickable link, NO trailing reflow.
  - **Critical:** if a clickable link appears in the email, the XSS escape (PR #42) regressed. Stop and file a P0.
  - **Observed:**

---

## Phase G: Tenant kill-switch

- [ ] **G1.** Admin (or super-admin) flips `EMAIL_NOTIFICATIONS=false` for the test tenant.
- [ ] **G2.** Trigger another `CHEQUE_DEPOSITED`.
  - **Expected:** in-app notification appears, but **NO outbox row** is created. Backend log shows `email.dispatch.skipped reason=feature_disabled`.
  - **Observed:**
- [ ] **G3.** Flip `EMAIL_NOTIFICATIONS=true` again, repeat the cheque trigger, confirm normal flow.

---

## Phase H: Multi-tenant isolation regression

- [ ] **H1.** Log in as a TENANT_ADMIN of a *different* tenant (any other production tenant).
- [ ] **H2.** Hit `GET /api/v1/admin/email/outbox`.
  - **Expected:** only their tenant's rows are returned. The Email QA Phase 1 rows **MUST NOT** appear.
  - **Observed:**

---

## Phase I: AR locale

- [ ] **I1.** Set the renter's preferred locale to `ar` (via profile or DB).
- [ ] **I2.** Trigger any event (e.g., `LEASE_CREATED` again).
  - **Expected:** email renders in Arabic, RTL layout. Tenant logo + name show. CTA still works.
  - **Observed:**

---

## Anomalies / Notes

_[Free-form bullets for anything unexpected.]_

---

## Sign-off

- Phase 1 production ready: ☐ yes / ☐ no
- Outstanding hotfixes opened: _[list PR numbers]_
- Date: _[…]_
- Tester: _[…]_
