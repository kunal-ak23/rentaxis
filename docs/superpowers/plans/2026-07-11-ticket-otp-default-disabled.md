# Disable Ticket-Close OTP by Default Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every tenant (new and existing) has `ticket_otp_required = false` by default, so closing a resolved maintenance ticket no longer requires OTP verification unless a superadmin re-enables it.

**Architecture:** Migration-only change. The OTP flow, the per-tenant toggle (`LandlordOrg.ticketOtpRequired`), the service logic that reads it (`MaintenanceTicketService.closeWithOtp`), and the superadmin UI to flip it all already exist and already work correctly — only the stored/default value changes.

**Tech Stack:** Liquibase (YAML changelog).

**Spec:** `docs/superpowers/specs/2026-07-11-ticket-otp-default-disabled-design.md`

---

## File Structure

**Create:**
- `backend/src/main/resources/db/changelog/changesets/65-ticket-otp-default-disabled.yaml`

**Modify:**
- `backend/src/main/resources/db/changelog/db.changelog-master.yaml`

No application code changes — `MaintenanceTicketService.closeWithOtp()` (`backend/src/main/java/com/datagami/rentaxis/core/service/MaintenanceTicketService.java:274-281`) already reads `LandlordOrg.getTicketOtpRequired()` and correctly skips OTP validation when it's `false`.

---

### Task 1: Flip `ticket_otp_required` default to `false` for all tenants

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/65-ticket-otp-default-disabled.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml`

- [ ] **Step 1: Create the Liquibase changeset**

Create `backend/src/main/resources/db/changelog/changesets/65-ticket-otp-default-disabled.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 65-ticket-otp-default-disabled
      author: rentaxis-system
      comment: "Disable ticket-close OTP requirement by default for all tenants (was true); still toggleable per tenant by SUPER_ADMIN"
      changes:
        - addDefaultValue:
            tableName: landlord_org
            columnName: ticket_otp_required
            defaultValueBoolean: false
        - update:
            tableName: landlord_org
            columns:
              - column:
                  name: ticket_otp_required
                  valueBoolean: false
```

- [ ] **Step 2: Register the changeset in the master changelog**

In `backend/src/main/resources/db/changelog/db.changelog-master.yaml`, after the `64-cheque-deposit-reminder-days.yaml` include (added by the cheque-deposit-reminder plan; if that plan hasn't run yet, add this after `63-payment-schedule-vat-amount.yaml` instead), add:

```yaml
  - include:
      file: db/changelog/changesets/65-ticket-otp-default-disabled.yaml
```

- [ ] **Step 3: Run the backend test suite to confirm the changeset applies cleanly**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL. The existing `@SpringBootTest`-based integration tests (e.g. `ChequeFailurePenaltyIT`) boot the full Spring context, which runs every Liquibase changeset on startup — if changeset 65 were malformed or conflicted with existing data, the context would fail to load and every one of those tests would error out. A clean pass is the verification that the migration is valid.

- [ ] **Step 4: Manual verification (optional, requires a running local stack)**

If you have the local dev stack running (`docker compose up -d` per the project's `CLAUDE.md`), confirm the flip took effect:

```bash
docker compose exec db psql -U postgres -d rentaxis -c "SELECT id, name, ticket_otp_required FROM landlord_org;"
```

Expected: every row shows `ticket_otp_required = f`, regardless of what it was before the migration.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/changelog/changesets/65-ticket-otp-default-disabled.yaml \
        backend/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "fix(tickets): disable OTP-required-to-close by default for all tenants"
```

---

That's the whole plan — one task, since the feature it depends on already exists end-to-end.
