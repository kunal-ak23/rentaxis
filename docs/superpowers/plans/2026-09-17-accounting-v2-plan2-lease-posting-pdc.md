# Accounting v2 — Plan 2: Lease Posting & PDC Register — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the lease a posting document (charge-type lines with per-line credit accounts, DRAFT → **Post** → `TCO` + one `PDR` per cheque, reversal to amend, renew chain, additive extend) and replace `payment_schedules` with a real `Cheque` register whose every status transition posts exactly one journal; penalties become proposed → approved assessments; receipts (cash, transfer, online) all clear a register row.

**Architecture:** `LeasePostingService` and `ChequeService` are the only writers of lease/cheque journals and both go through Plan 1's `PostingService` with roles. Operational "due/overdue" predicates live on the register, never on the ledger. `payment_schedules`, `lease_charges`, `payment_penalties`, `penalty_payments` are dropped at the end of this plan (wipe-and-reseed, spec D4). Plan 3 (recognition/termination/settlement) hooks in via `LeasePostedEvent`, `LeaseAmendedEvent` and `ChequeService.returnCheque`.

**Tech Stack:** as Plan 1 (Java 21 / Spring Boot 4 / JPA / Liquibase / Postgres 16 / Testcontainers; Next.js 16 / TS / Tailwind 4 / next-intl / Vitest / Playwright).

**Spec:** `docs/superpowers/specs/2026-09-17-accounting-v2-design.md` §6 (lease as posting document), §7 (PDC register, penalties, operational predicates), §9.3 (receipts), §11 (Leasing, Cheque register, Renter portal screens). **Depends on Plan 1** (`docs/superpowers/plans/2026-09-17-accounting-v2-plan1-ledger-core.md`) being merged: `PostingService`, `PostingRequest.{Dimensions,Line,dr,cr}`, `AccountResolver.resolve/resolveAll`, `AccountRole`, `JournalDocType`, `JournalSourceType`, `TenantFiscalSettingsService`, `LedgerQueryService`, `UserRole.ACCOUNTANT`, `ledgerApi`, `AccountPicker`, `LedgerTable`, `Ledger` i18n namespace, RBAC `canPostJournals`.

## Global Constraints

- Everything in Plan 1's Global Constraints applies. Liquibase numbers for this plan: **83** (new tables/columns) and **84** (drop v1 lease/cheque tables). Plan 3 uses 85–86, Plan 4 uses 87–88.
- Every cheque transition posts **at most one** journal and is executed under `ChequeRepository.findByIdForUpdate` (NOWAIT, translated to a 400 "try again" exactly as `PaymentScheduleService.lockAndRequireStatus` does today).
- No account codes in code. Roles only. The only account ids stored on business rows are the per-line `credit_account_id` (user-editable) and the per-cheque `debit_account_id` (bank leaf).
- Lease money fields: `lease_lines` are the source of truth. `leases.rent_amount` and `leases.deposit_amount` are **kept as derived mirrors** (Σ RENT lines net, Σ DEPOSIT lines net), recomputed by `LeaseService.syncDerivedTotals` whenever lines change, so `ContractGenerationService`, `UnitListingService`, renewals, dashboards and the unit's `actualRent` keep working unmodified. This is a deliberate narrowing of spec §6.3 ("removed") — record it in the spec (Task 4 Step 9).
- `payment_schedules` id space is gone. Everything that referenced a schedule id (online payments, penalties, meeting `payment_schedule_ids`, email payloads, receipts) references a **cheque id** after this plan.
- Web: lease editor is rebuilt around PACT's layout; the finance "Payments" page becomes the **Cheque register**; mobile stays frozen (Plan 5 hides its screens).
- Branch `feat/accounting-v2-lease-posting` cut from `main` after Plan 1's PR is merged.

---

## File structure

**Backend — new**
- `domain/entity/enums/ChargeBehaviour.java`, `ChequeStatus.java`, `ChequeMode.java`, `PenaltyReason.java`, `PenaltyAssessmentStatus.java`
- `domain/entity/ChargeType.java`, `LeaseLine.java`, `Cheque.java`, `PenaltyAssessment.java`
- `domain/repository/ChargeTypeRepository.java`, `LeaseLineRepository.java`, `ChequeRepository.java`, `PenaltyAssessmentRepository.java`
- `core/service/lease/ChargeTypeService.java`, `LeasePostingService.java`, `LeaseRenewalService.java` (renew/extend), `ChequeGenerationService.java`, `LeasePostedEvent.java`, `LeaseAmendedEvent.java`
- `core/service/cheque/ChequeService.java`, `ChequeQueryService.java`, `ChequeDueRules.java`
- `core/service/penalty/PenaltyAssessmentService.java`, `PenaltyRuleEngine.java`
- `api/ChargeTypeController.java`, `api/ChequeController.java`, `api/PenaltyAssessmentController.java`
- `api/dto/lease/*.java` (`LeaseLineDTO`, `LeaseLineInput`, `LeaseDetailDTO`, `GenerateChequesRequest`, `ChequeRowInput`, `PostLeaseResponse`), `api/dto/cheque/*.java` (`ChequeDTO`, `ChequeActionRequest`, `ReplaceChequeRequest`, `DepositBatchRequest`), `api/dto/penalty/*.java`
- `db/changelog/changesets/83-lease-posting-and-cheques.yaml`, `84-drop-v1-schedules.yaml`

**Backend — modified**
- `Lease.java` (+contract fields, chain), `LeaseStatus` (+`RENEWED`), `LeaseService.java` (lines, derived totals, delete draft, terminate → cancel cheques), `LeaseController.java` (+post, amend-lines, renew, cheques endpoints), `LeaseDTO`/`CreateLeaseDTO` (+lines, contract fields), `ChequeRoundingCalculator.java` (+step 10), `LeaseExpirationJob.java`, `OnlinePaymentService.java` + `OnlinePayment.java` (schedule → cheque), `WebhookService.java`, `RentReceiptService.java`, `NotificationScheduler.java`, `NotificationService.java`, `DashboardService.java`, `ContractGenerationService.java`, `MeetingService.java` + `MeetingDetail.java`, `cheque/ChequeImageRetentionJob.java`, `cheque/ChequeExtractionService.java` (bulk attach target), `PortfolioImportPersistService.java` (minimal: lines + cheque rows, no schedules), `LeaseAccessPolicy.java` (+ACCOUNTANT sees all), `FineConfig.java`/`FineConfigResolver.java`/`LandlordOrgFineSettings.java`/`RentCollectionSettings.java` (+bounces_before_penalty, auto_propose), `TenantGatewayConfig.java` (+settlement_account_id), `PropertyService`/`Property.java` (+code), email payloads `ChequePayload`/`PaymentReminderPayload`/`RentReceiptPayload`/`PenaltyPayload` (schedule id → cheque id)

**Backend — deleted (Task 12)**
- `PaymentSchedule`, `PaymentScheduleRepository`, `PaymentScheduleService`, `PaymentScheduleController`, `PaymentScheduleDTO`, `RenterPaymentScheduleDTO`, `UpdatePaymentScheduleDTO`, `UpdatePaymentStatusDTO`, `MarkFailedRequestDTO`, `MarkFailedResponseDTO`, `MarkFailedResult`, `PaymentPreviewDTO`, `LeasePaymentStatsDTO`, `AgingReportDTO` (rebuilt on cheques in `ChequeQueryService`), `LeaseCharge`, `LeaseChargeRepository`, `LeaseChargeDTO`, `ChargeFrequency`, `PaymentPenalty`, `PenaltyPayment`, `PaymentPenaltyRepository`, `PenaltyPaymentRepository`, `PenaltyService`, `PenaltyPaymentService`, `PenaltyProcessingService`, `PenaltyController`, `PenaltyDTO`, `PenaltyPaymentDTO`, `RecordPenaltyPaymentRequestDTO`, `WaivePenaltyRequestDTO`, and the tests listed in Task 12.

**Web — new**
- `web/src/lib/api/leasing.ts` (types + `leaseApi`, `chequeApi`, `penaltyApi`, `chargeTypeApi`)
- `web/src/components/leases/LeaseLinesGrid.tsx`, `ChequeGrid.tsx`, `LeaseJournalsTab.tsx`, `PostLeaseDialog.tsx`, `AmendLinesDialog.tsx`, `RenewLeaseDialog.tsx`, `ExtendLeaseDialog.tsx`
- `web/src/components/cheques/ChequeStatusBadge.tsx`, `DepositBatchDialog.tsx`, `ReplaceChequeDialog.tsx`, `BounceChequeDialog.tsx`, `ReceiveCashDialog.tsx`
- `web/src/app/[locale]/dashboard/finance/cheques/page.tsx` (register), `cheques/collection/page.tsx`, `cheques/return-replace/page.tsx`, `cheques/post-dated/page.tsx`
- `web/src/app/[locale]/dashboard/finance/penalties/page.tsx`
- `web/src/app/[locale]/dashboard/settings/charge-types/page.tsx`
- `web/src/components/renter/PayOnlineButton.tsx` (Razorpay checkout.js)

**Web — modified / deleted**
- `dashboard/leases/LeaseWizard.tsx` (steps: parties → terms → **lines** → **cheques** → review), `leases/[id]/page.tsx` (PACT layout, actions), `leases/page.tsx` (RENEWED status, chain, Bulk post), `renter-portal/payments/page.tsx` (due rows + pay), `renter-portal/penalties/page.tsx`, `MvpSidebar.tsx`, `rbac.ts`, messages; **deleted**: `finance/payments/page.tsx` (+ tests), `leases/PaymentScheduleEditor.tsx`, `components/payments/CollectChequeDialog.tsx`, `MarkChequeFailedDialog.tsx`, `components/penalties/RecordPenaltyPaymentDialog.tsx`, `e2e/finance/payments-pdc.spec.ts`

---

### Task 1: Changeset 83 — charge types, lease lines, cheques, penalty assessments, lease/property/fine/gateway columns

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/83-lease-posting-and-cheques.yaml`; append include to master
- Test: `backend/src/test/java/com/datagami/rentaxis/domain/LeaseChequeSchemaIT.java`

**Interfaces:**
- Produces tables `charge_types`, `lease_lines`, `cheques`, `penalty_assessments`; columns on `leases` (`contract_date, total_days, grace_period_days, renewed_from_lease_id, chain_id, receivable_account_id, income_account_id, posting_journal_id, posted_at, posted_by, first_due_date`), `properties.code`, `landlord_org_fine_settings.{bounces_before_penalty, auto_propose_cheque_return, auto_propose_late_payment}`, `rent_collection_settings.{bounces_before_penalty}`, `tenant_gateway_configs.settlement_account_id`, `online_payments.cheque_id` (nullable now; NOT NULL after Task 12), `meeting_details.cheque_ids uuid[]`.

- [ ] **Step 1: Failing schema test** — same shape as Plan 1's `LedgerSchemaIT`: assert the four tables exist, `cheques` has a CHECK `ck_cheques_amount_positive`, a unique index `ux_cheques_lease_number` on `(lease_id, cheque_number) WHERE cheque_number IS NOT NULL AND mode = 'PDC'`, `leases.chain_id` exists, and `properties.code` is unique per tenant (insert two properties with the same code in one tenant → `DataIntegrityViolation`).

- [ ] **Step 2: Changeset**

```yaml
databaseChangeLog:
  # Accounting v2 plan 2 (spec §6-§7). The lease becomes a posting document:
  # charge-type lines with an editable credit account, a contract date and a
  # renewal chain. Cheques become their own entity (the PDC register) instead
  # of columns on payment_schedules. Penalties become assessments that must be
  # approved before they post. payment_schedules / lease_charges /
  # payment_penalties / penalty_payments are dropped in 84 once the code has
  # moved over — wipe-and-reseed was agreed (spec D4), so nothing is migrated.
  - changeSet:
      id: 83-lease-posting-and-cheques
      author: claude
      changes:
        - createTable:
            tableName: charge_types
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: code, type: varchar(40), constraints: { nullable: false } }
              - column: { name: name_en, type: varchar(120), constraints: { nullable: false } }
              - column: { name: name_ar, type: varchar(120) }
              - column: { name: role, type: varchar(40), constraints: { nullable: false } }
              - column: { name: behaviour, type: varchar(20), constraints: { nullable: false } }
              - column: { name: vat_applicable_default, type: boolean, constraints: { nullable: false }, defaultValueBoolean: false }
              - column: { name: active, type: boolean, constraints: { nullable: false }, defaultValueBoolean: true }
              - column: { name: display_order, type: int, constraints: { nullable: false }, defaultValueNumeric: 0 }
        - addUniqueConstraint: { tableName: charge_types, columnNames: "tenant_id, code", constraintName: uq_charge_types_tenant_code }

        - addColumn:
            tableName: properties
            columns:
              - column: { name: code, type: varchar(20) }
        - sql:
            sql: CREATE UNIQUE INDEX ux_properties_tenant_code ON properties (tenant_id, code) WHERE code IS NOT NULL

        - addColumn:
            tableName: leases
            columns:
              - column: { name: contract_date, type: date }
              - column: { name: total_days, type: int }
              - column: { name: grace_period_days, type: int, constraints: { nullable: false }, defaultValueNumeric: 0 }
              - column: { name: first_due_date, type: date }
              - column: { name: renewed_from_lease_id, type: uuid, constraints: { foreignKeyName: fk_leases_renewed_from, referencedTableName: leases, referencedColumnNames: id } }
              - column: { name: chain_id, type: uuid }
              - column: { name: receivable_account_id, type: uuid, constraints: { foreignKeyName: fk_leases_receivable_account, referencedTableName: accounts, referencedColumnNames: id } }
              - column: { name: income_account_id, type: uuid, constraints: { foreignKeyName: fk_leases_income_account, referencedTableName: accounts, referencedColumnNames: id } }
              - column: { name: posting_journal_id, type: uuid, constraints: { foreignKeyName: fk_leases_posting_journal, referencedTableName: journal_entries, referencedColumnNames: id } }
              - column: { name: posted_at, type: timestamptz }
              - column: { name: posted_by, type: uuid }
        - sql:
            comment: Existing rows get a chain of their own and a contract date equal to agreement/start date
            sql: >-
              UPDATE leases SET chain_id = id WHERE chain_id IS NULL;
              UPDATE leases SET contract_date = COALESCE(agreement_date, start_date) WHERE contract_date IS NULL;
              UPDATE leases SET total_days = (end_date - start_date) + 1 WHERE total_days IS NULL
        - createIndex: { tableName: leases, indexName: idx_leases_chain, columns: [ { column: { name: chain_id } } ] }

        - createTable:
            tableName: lease_lines
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: lease_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_lease_lines_lease, referencedTableName: leases, referencedColumnNames: id, deleteCascade: true } }
              - column: { name: seq_no, type: int, constraints: { nullable: false } }
              - column: { name: charge_type_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_lease_lines_charge_type, referencedTableName: charge_types, referencedColumnNames: id } }
              - column: { name: credit_account_id, type: uuid, constraints: { foreignKeyName: fk_lease_lines_credit_account, referencedTableName: accounts, referencedColumnNames: id } }
              - column: { name: gross_amount, type: "decimal(14,2)", constraints: { nullable: false }, defaultValueNumeric: 0 }
              - column: { name: discount_amount, type: "decimal(14,2)", constraints: { nullable: false }, defaultValueNumeric: 0 }
              - column: { name: net_amount, type: "decimal(14,2)", constraints: { nullable: false }, defaultValueNumeric: 0 }
              - column: { name: narration, type: varchar(255) }
              - column: { name: vat_applicable, type: boolean, constraints: { nullable: false }, defaultValueBoolean: false }
              - column: { name: period_start, type: date }
              - column: { name: period_end, type: date }
        - createIndex: { tableName: lease_lines, indexName: idx_lease_lines_lease, columns: [ { column: { name: lease_id } } ] }
        - sql:
            sql: ALTER TABLE lease_lines ADD CONSTRAINT ck_lease_lines_net CHECK (net_amount = gross_amount - discount_amount AND net_amount >= 0)

        - createTable:
            tableName: cheques
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: lease_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_cheques_lease, referencedTableName: leases, referencedColumnNames: id } }
              - column: { name: property_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_cheques_property, referencedTableName: properties, referencedColumnNames: id } }
              - column: { name: unit_id, type: uuid, constraints: { foreignKeyName: fk_cheques_unit, referencedTableName: units, referencedColumnNames: id } }
              - column: { name: renter_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_cheques_renter, referencedTableName: renters, referencedColumnNames: id } }
              - column: { name: seq_no, type: int, constraints: { nullable: false } }
              - column: { name: posting_date, type: date, constraints: { nullable: false } }
              - column: { name: cheque_number, type: varchar(255) }
              - column: { name: cheque_date, type: date, constraints: { nullable: false } }
              - column: { name: payee_bank, type: varchar(100) }
              - column: { name: payer_name, type: varchar(200) }
              - column: { name: debit_account_id, type: uuid, constraints: { foreignKeyName: fk_cheques_debit_account, referencedTableName: accounts, referencedColumnNames: id } }
              - column: { name: amount, type: "decimal(14,2)", constraints: { nullable: false } }
              - column: { name: narration, type: varchar(255) }
              - column: { name: mode, type: varchar(10), constraints: { nullable: false }, defaultValue: PDC }
              - column: { name: status, type: varchar(16), constraints: { nullable: false }, defaultValue: DRAFT }
              - column: { name: failure_reason, type: varchar(30) }
              - column: { name: replaces_id, type: uuid, constraints: { foreignKeyName: fk_cheques_replaces, referencedTableName: cheques, referencedColumnNames: id } }
              - column: { name: replaced_by_id, type: uuid, constraints: { foreignKeyName: fk_cheques_replaced_by, referencedTableName: cheques, referencedColumnNames: id } }
              - column: { name: image_url, type: varchar(500) }
              - column: { name: image_blob_path, type: varchar(500) }
              - column: { name: image_uploaded_at, type: timestamptz }
              - column: { name: notes, type: text }
              - column: { name: deposited_at, type: date }
              - column: { name: cleared_at, type: date }
              - column: { name: bounced_at, type: date }
              - column: { name: returned_at, type: date }
              - column: { name: status_changed_at, type: timestamptz }
              - column: { name: pdr_journal_id, type: uuid, constraints: { foreignKeyName: fk_cheques_pdr_journal, referencedTableName: journal_entries, referencedColumnNames: id } }
              - column: { name: crt_journal_id, type: uuid, constraints: { foreignKeyName: fk_cheques_crt_journal, referencedTableName: journal_entries, referencedColumnNames: id } }
              - column: { name: cbr_journal_id, type: uuid, constraints: { foreignKeyName: fk_cheques_cbr_journal, referencedTableName: journal_entries, referencedColumnNames: id } }
              - column: { name: penalty_assessment_id, type: uuid }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
        - sql:
            sql: |
              ALTER TABLE cheques ADD CONSTRAINT ck_cheques_amount_positive CHECK (amount > 0);
              CREATE UNIQUE INDEX ux_cheques_lease_number ON cheques (lease_id, cheque_number) WHERE cheque_number IS NOT NULL AND mode = 'PDC';
              CREATE INDEX idx_cheques_tenant_status_date ON cheques (tenant_id, status, cheque_date);
              CREATE INDEX idx_cheques_lease ON cheques (lease_id);
              CREATE INDEX idx_cheques_renter ON cheques (renter_id);
              CREATE INDEX idx_cheques_property ON cheques (property_id);
              CREATE INDEX idx_cheques_image_purge ON cheques (cheque_date) WHERE image_blob_path IS NOT NULL;

        - createTable:
            tableName: penalty_assessments
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: lease_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_pa_lease, referencedTableName: leases, referencedColumnNames: id } }
              - column: { name: cheque_id, type: uuid, constraints: { foreignKeyName: fk_pa_cheque, referencedTableName: cheques, referencedColumnNames: id } }
              - column: { name: renter_id, type: uuid, constraints: { nullable: false } }
              - column: { name: property_id, type: uuid, constraints: { nullable: false } }
              - column: { name: reason, type: varchar(20), constraints: { nullable: false } }
              - column: { name: amount, type: "decimal(14,2)", constraints: { nullable: false } }
              - column: { name: description, type: text }
              - column: { name: status, type: varchar(12), constraints: { nullable: false }, defaultValue: PROPOSED }
              - column: { name: proposed_by, type: uuid }
              - column: { name: proposed_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
              - column: { name: approved_by, type: uuid }
              - column: { name: approved_at, type: timestamptz }
              - column: { name: journal_id, type: uuid, constraints: { foreignKeyName: fk_pa_journal, referencedTableName: journal_entries, referencedColumnNames: id } }
              - column: { name: collection_cheque_id, type: uuid, constraints: { foreignKeyName: fk_pa_collection_cheque, referencedTableName: cheques, referencedColumnNames: id } }
              - column: { name: resolution_note, type: text }
        - sql:
            sql: |
              ALTER TABLE penalty_assessments ADD CONSTRAINT ck_pa_amount_positive CHECK (amount > 0);
              CREATE INDEX idx_pa_tenant_status ON penalty_assessments (tenant_id, status);
              CREATE INDEX idx_pa_lease ON penalty_assessments (lease_id);
              ALTER TABLE cheques ADD CONSTRAINT fk_cheques_penalty_assessment FOREIGN KEY (penalty_assessment_id) REFERENCES penalty_assessments (id);

        - addColumn:
            tableName: landlord_org_fine_settings
            columns:
              - column: { name: bounces_before_penalty, type: int, constraints: { nullable: false }, defaultValueNumeric: 2 }
              - column: { name: auto_propose_cheque_return, type: boolean, constraints: { nullable: false }, defaultValueBoolean: true }
              - column: { name: auto_propose_late_payment, type: boolean, constraints: { nullable: false }, defaultValueBoolean: false }
        - addColumn:
            tableName: rent_collection_settings
            columns:
              - column: { name: bounces_before_penalty, type: int }
        - addColumn:
            tableName: tenant_gateway_configs
            columns:
              - column: { name: settlement_account_id, type: uuid, constraints: { foreignKeyName: fk_tgc_settlement_account, referencedTableName: accounts, referencedColumnNames: id } }
        - addColumn:
            tableName: online_payments
            columns:
              - column: { name: cheque_id, type: uuid, constraints: { foreignKeyName: fk_online_payment_cheque, referencedTableName: cheques, referencedColumnNames: id } }
        - addColumn:
            tableName: meeting_details
            columns:
              - column: { name: cheque_ids, type: "uuid[]" }
      rollback:
        - sql:
            sql: |
              ALTER TABLE meeting_details DROP COLUMN IF EXISTS cheque_ids;
              ALTER TABLE online_payments DROP COLUMN IF EXISTS cheque_id;
              ALTER TABLE tenant_gateway_configs DROP COLUMN IF EXISTS settlement_account_id;
              ALTER TABLE rent_collection_settings DROP COLUMN IF EXISTS bounces_before_penalty;
              ALTER TABLE landlord_org_fine_settings DROP COLUMN IF EXISTS bounces_before_penalty, DROP COLUMN IF EXISTS auto_propose_cheque_return, DROP COLUMN IF EXISTS auto_propose_late_payment;
              DROP TABLE IF EXISTS penalty_assessments, cheques, lease_lines, charge_types;
              ALTER TABLE leases DROP COLUMN IF EXISTS contract_date, DROP COLUMN IF EXISTS total_days, DROP COLUMN IF EXISTS grace_period_days, DROP COLUMN IF EXISTS first_due_date, DROP COLUMN IF EXISTS renewed_from_lease_id, DROP COLUMN IF EXISTS chain_id, DROP COLUMN IF EXISTS receivable_account_id, DROP COLUMN IF EXISTS income_account_id, DROP COLUMN IF EXISTS posting_journal_id, DROP COLUMN IF EXISTS posted_at, DROP COLUMN IF EXISTS posted_by;
              DROP INDEX IF EXISTS ux_properties_tenant_code; ALTER TABLE properties DROP COLUMN IF EXISTS code;
```

- [ ] **Step 3: Run the schema IT** → PASS. Commit: `feat(leasing): changeset 83 — charge types, lease lines, cheque register, penalty assessments`.

---

### Task 2: ChargeType catalogue

**Files:**
- Create: `domain/entity/enums/ChargeBehaviour.java`, `domain/entity/ChargeType.java`, `domain/repository/ChargeTypeRepository.java`, `core/service/lease/ChargeTypeService.java`, `api/ChargeTypeController.java`, `api/dto/lease/ChargeTypeDTO.java`
- Modify: `api/AccountController.seedDefaultAccounts` (also `chargeTypeService.seedDefaults()`)
- Test: `core/service/lease/ChargeTypeServiceIT.java`

**Interfaces:**
- `enum ChargeBehaviour { RENT, DEPOSIT, FEE }`
- `ChargeType` fields: `code, nameEn, nameAr, role (AccountRole), behaviour, vatApplicableDefault, active, displayOrder`
- `ChargeTypeService.seedDefaults()` (idempotent), `list(boolean activeOnly)`, `create(ChargeTypeDTO)`, `update(id, ChargeTypeDTO)`, `getByCode(String)`
- `record ChargeTypeDTO(UUID id, String code, String nameEn, String nameAr, AccountRole role, ChargeBehaviour behaviour, boolean vatApplicableDefault, boolean active, int displayOrder)`
- Endpoints `GET/POST /api/v1/finance/charge-types`, `PUT /api/v1/finance/charge-types/{id}` — read: SA/TA/ACCOUNTANT/PM; write: SA/TA/ACCOUNTANT.
- Seeded rows (code → nameEn, role, behaviour): `RENT` → Rent, RENTAL_INCOME? **No** — RENT lines credit `ADVANCE_RENT` (spec §6.1); role stored on the type is the *credit* role: `RENT → ADVANCE_RENT/RENT`, `SECURITY_DEPOSIT → SECURITY_DEPOSIT/DEPOSIT`, `ADMIN_FEE → ADMIN_FEE/FEE`, `PARKING_DEPOSIT → PARKING_DEPOSIT/DEPOSIT`, `COOLING → COOLING_CHARGES/FEE`, `PARKING_FEE → PARKING_INCOME/FEE`, `MAINTENANCE → MAINTENANCE_CHARGES/FEE`.

- [ ] **Step 1: Failing IT** — `seedDefaults()` twice leaves exactly 7 rows; `getByCode("RENT").getRole() == ADVANCE_RENT` and `behaviour == RENT`; creating a type with an existing code → `DataIntegrityViolationException` surfaced as 409; a `DEPOSIT` behaviour type must have a LIABILITY-typed role (validate via a static map `AccountRole → expected AccountType` in `ChargeTypeService`: `ADVANCE_RENT, SECURITY_DEPOSIT, PARKING_DEPOSIT → LIABILITY`, all others `INCOME`) → `BusinessRuleViolationException` when mismatched.

- [ ] **Step 2: Implement** entity (`BaseTenantEntity`, `@Enumerated(STRING)` for `role`/`behaviour`), repository (`findByCode`, `findAllByOrderByDisplayOrderAscCodeAsc`, `findByActiveTrueOrderByDisplayOrderAscCodeAsc`), service, controller (`@PreAuthorize` per above), DTO mapping. Seed list as a `List<ChargeTypeDTO>` constant in the service; `seedDefaults` inserts those whose `code` is missing.

- [ ] **Step 3: Run, commit** `feat(leasing): charge-type catalogue with seeded PACT particulars`.

---

### Task 3: Cheque entity, enums, repository, DTO

**Files:**
- Create: `domain/entity/enums/ChequeStatus.java`, `ChequeMode.java`, `domain/entity/Cheque.java`, `domain/repository/ChequeRepository.java`, `api/dto/cheque/ChequeDTO.java`, `core/service/cheque/ChequeMapper.java`
- Test: `domain/repository/ChequeRepositoryIT.java`

**Interfaces:**
```java
enum ChequeStatus { DRAFT, REGISTERED, DEPOSITED, CLEARED, BOUNCED, REPLACED, CANCELLED, RETURNED, ONLINE_PENDING;
    public boolean isUncleared() { return this == REGISTERED || this == DEPOSITED || this == ONLINE_PENDING; }
    public boolean isTerminal()  { return this == CLEARED || this == REPLACED || this == CANCELLED || this == RETURNED; } }
enum ChequeMode { PDC, CASH, TRANSFER, ONLINE }
```
`Cheque` (BaseTenantEntity) — columns exactly as changeset 83; relations: `@ManyToOne(LAZY) lease, property, unit, renter, debitAccount (Account), replaces (Cheque), replacedBy (Cheque)`; `@Enumerated(STRING) mode, status, failureReason (ChequeFailureReason)`; plain UUID `pdrJournalId, crtJournalId, cbrJournalId, penaltyAssessmentId`.
`ChequeRepository`:
```java
List<Cheque> findByLease_IdOrderBySeqNoAsc(UUID leaseId);
@Lock(PESSIMISTIC_WRITE) @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
@Query("select c from Cheque c where c.id = :id") Optional<Cheque> findByIdForUpdate(UUID id);
@Lock(PESSIMISTIC_WRITE) @QueryHints(...) @Query("select c from Cheque c where c.id in :ids") List<Cheque> findAllByIdForUpdate(Collection<UUID> ids);
List<Cheque> findByRenter_IdAndStatusInOrderByChequeDateAsc(UUID renterId, Collection<ChequeStatus> statuses);
@Query("""select c from Cheque c where (:propertyId is null or c.property.id = :propertyId)
   and (:status is null or c.status = :status) and (:mode is null or c.mode = :mode)
   and (:from is null or c.chequeDate >= :from) and (:to is null or c.chequeDate <= :to)
   and c.lease.status not in ('DRAFT','PENDING_SIGNATURE')
   and (:search is null or lower(c.lease.renter.nameEn) like :search or lower(c.chequeNumber) like :search or lower(c.unit.unitNumber) like :search)""")
Page<Cheque> search(UUID propertyId, ChequeStatus status, ChequeMode mode, LocalDate from, LocalDate to, String search, Pageable p);
@Query("select c from Cheque c where c.status in ('REGISTERED','DEPOSITED') and c.chequeDate <= :today and c.lease.status not in ('DRAFT','PENDING_SIGNATURE') and (:propertyId is null or c.property.id = :propertyId)")
Page<Cheque> findDue(UUID propertyId, LocalDate today, Pageable p);
@Query("select c from Cheque c where c.status = 'REGISTERED' and c.mode = 'PDC' and c.chequeDate <= :today and (:propertyId is null or c.property.id = :propertyId)")
Page<Cheque> findToDeposit(UUID propertyId, LocalDate today, Pageable p);
long countByLease_IdAndStatusIn(UUID leaseId, Collection<ChequeStatus> statuses);
long countByLease_IdAndBouncedAtIsNotNull(UUID leaseId);
@Query("select c from Cheque c where c.imageBlobPath is not null and c.chequeDate < :cutoff") List<Cheque> findImagesOlderThan(LocalDate cutoff);
@Query("select coalesce(sum(c.amount),0) from Cheque c where c.status = 'CLEARED' and c.clearedAt >= :from and c.clearedAt < :to and c.lease.status not in ('DRAFT','PENDING_SIGNATURE')") BigDecimal sumClearedBetween(LocalDate from, LocalDate to);
```
`record ChequeDTO(UUID id, UUID leaseId, UUID propertyId, UUID unitId, UUID renterId, String propertyName, String unitIdentifier, String renterName, int seqNo, LocalDate postingDate, String chequeNumber, LocalDate chequeDate, String payeeBank, String payerName, UUID debitAccountId, String debitAccountName, BigDecimal amount, String narration, ChequeMode mode, ChequeStatus status, ChequeFailureReason failureReason, UUID replacesId, UUID replacedById, String imageUrl, LocalDate depositedAt, LocalDate clearedAt, LocalDate bouncedAt, LocalDate returnedAt, UUID pdrJournalId, UUID crtJournalId, UUID cbrJournalId, UUID penaltyAssessmentId, boolean due, boolean overdue, int daysOverdue)`; `ChequeMapper.toDto(Cheque, LocalDate today, int graceDays)` fills `due/overdue/daysOverdue` from `ChequeDueRules` (Task 7).

- [ ] **Step 1: Failing repository IT** — insert a lease with 3 cheques (REGISTERED dated yesterday, REGISTERED dated next month, CLEARED); `findDue(null, today)` returns 1; `findToDeposit` returns 1; `search(propertyId, null, null, null, null, "%olv%")` matches by unit number; `findByIdForUpdate` in two threads → second gets `PessimisticLockingFailureException`.
- [ ] **Step 2: Implement** entity/enums/repo/DTO/mapper (`due/overdue` computed as: `due = status ∈ {REGISTERED, DEPOSITED} && chequeDate ≤ today || status == BOUNCED`; `overdue = due && chequeDate.plusDays(graceDays) < today`; `daysOverdue = max(0, DAYS between chequeDate+grace and today)`).
- [ ] **Step 3: Run, commit** `feat(cheques): Cheque entity, status/mode enums, register queries`.

---

### Task 4: LeaseLine, lease header fields, draft create/update with lines, derived totals

**Files:**
- Create: `domain/entity/LeaseLine.java`, `domain/repository/LeaseLineRepository.java`, `api/dto/lease/LeaseLineInput.java`, `api/dto/lease/LeaseLineDTO.java`
- Modify: `domain/entity/Lease.java`, `domain/entity/enums/LeaseStatus.java` (+`RENEWED`), `api/dto/CreateLeaseDTO.java`, `api/dto/LeaseDTO.java`, `core/service/LeaseService.java`, `domain/entity/Property.java` (+`code`)
- Test: `core/service/lease/LeaseLinesIT.java`; delete `api/dto/LeaseChargeValidationTest.java`

**Interfaces:**
- `LeaseLine` (BaseTenantEntity): `lease (ManyToOne LAZY), seqNo, chargeType (ManyToOne EAGER), creditAccount (ManyToOne LAZY, nullable), grossAmount, discountAmount, netAmount, narration, vatApplicable, periodStart, periodEnd`. `net = gross − discount` enforced in `LeaseService.applyLines`.
- `Lease` gains: `contractDate, totalDays, gracePeriodDays, firstDueDate, renewedFromLeaseId (UUID), chainId (UUID), receivableAccountId (UUID), incomeAccountId (UUID), postingJournalId (UUID), postedAt (Instant), postedBy (UUID)`. Keeps `rentAmount`, `depositAmount` (derived), `paymentTerms`, `installmentDistribution`, `paymentMethod`, `depositPaymentMethod`, `agreementDate`, `rentVatApplicable`, `contractNumber`. **Drops** `monthlyRent` (column stays until 84; field removed now, JPA ignores it).
- `record LeaseLineInput(UUID chargeTypeId, String chargeTypeCode, BigDecimal grossAmount, BigDecimal discountAmount, String narration, Boolean vatApplicable, UUID creditAccountId, LocalDate periodStart, LocalDate periodEnd)` — `chargeTypeId` **or** `chargeTypeCode` (import/seed convenience).
- `record LeaseLineDTO(UUID id, int seqNo, UUID chargeTypeId, String chargeTypeCode, String chargeTypeName, String behaviour, UUID creditAccountId, String creditAccountCode, String creditAccountName, BigDecimal grossAmount, BigDecimal discountAmount, BigDecimal netAmount, String narration, boolean vatApplicable, LocalDate periodStart, LocalDate periodEnd)`.
- `CreateLeaseDTO` gains `contractDate`, `gracePeriodDays`, `firstDueDate`, `List<LeaseLineInput> lines` (replaces `charges`, `rentAmount`, `monthlyRent`, `depositAmount`, `bookingDeposit`); keeps `unitId, renterId, startDate, endDate, paymentTerms, installmentDistribution, paymentMethod, depositPaymentMethod, ejariNumber, agreementDate, rentVatApplicable`.
- `LeaseDTO` gains `contractDate, totalDays, gracePeriodDays, firstDueDate, renewedFromLeaseId, chainId, receivableAccountId, incomeAccountId, postingJournalId, postedAt, contractValue, lines (List<LeaseLineDTO>), propertyCode, displayContractNumber` ("GLA_B1/681" when property has a code, else the number).
- `LeaseService`: `createDraftLease`, `updateDraftLease` take lines; `applyLines(Lease, List<LeaseLineInput>)` (delete-then-insert, resolves default credit account via `AccountResolver.resolve(chargeType.role, propertyId)` when `creditAccountId` is null and a mapping exists — leaves null otherwise so the guard at post time reports it), `syncDerivedTotals(Lease)` sets `rentAmount = Σ RENT net`, `depositAmount = Σ DEPOSIT net`, `totalDays = days inclusive`, `contractValue()` helper; `getLines(leaseId)`.

- [ ] **Step 1: Failing IT**

```java
@SpringBootTest @Testcontainers
class LeaseLinesIT {
    // fixtures: tenant, CoA seed + template + charge types, property (creates account set), unit, renter — helper `LeaseTestFixtures` in testsupport/ (create it here; Plans 3/5 reuse it)
    @Test void draftWithLinesDerivesTotalsAndDefaultCreditAccounts() {
        CreateLeaseDTO dto = fixtures.draftDto(unit, renter, LocalDate.of(2026,9,24), LocalDate.of(2027,9,23), List.of(
            new LeaseLineInput(null, "RENT", bd("51000"), bd("0"), null, null, null, null, null),
            new LeaseLineInput(null, "ADMIN_FEE", bd("2000"), bd("0"), null, null, null, null, null),
            new LeaseLineInput(null, "SECURITY_DEPOSIT", bd("3000"), bd("0"), null, null, null, null, null)));
        LeaseDTO lease = leaseService.createDraftLease(dto);
        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.DRAFT);
        assertThat(lease.getContractValue()).isEqualByComparingTo("56000");
        assertThat(lease.getRentAmount()).isEqualByComparingTo("51000");
        assertThat(lease.getDepositAmount()).isEqualByComparingTo("3000");
        assertThat(lease.getTotalDays()).isEqualTo(365);
        assertThat(lease.getChainId()).isEqualTo(lease.getId());
        LeaseLineDTO rent = lease.getLines().get(0);
        assertThat(rent.creditAccountName()).isEqualTo("Advance Rent - " + fixtures.propertyName());
        assertThat(rent.periodStart()).isEqualTo(LocalDate.of(2026,9,24));
        assertThat(rent.periodEnd()).isEqualTo(LocalDate.of(2027,9,23));
    }
    @Test void discountReducesNetAndCannotExceedGross() {
        // gross 51000 discount 1000 -> net 50000; discount 60000 -> BusinessRuleViolationException("Discount cannot exceed")
    }
    @Test void updateDraftReplacesLinesAndRecomputes() { /* update with two lines -> contractValue 53000, deposit 0 */ }
    @Test void onlyDraftCanChangeLines() { /* set status ACTIVE via repo -> updateDraftLease throws "Only DRAFT" */ }
    @Test void unknownChargeTypeCodeIs400() { /* code "XYZ" -> BusinessRuleViolationException */ }
}
```
Write these five tests in full (the bodies follow the comments; use `assertThatThrownBy`).

- [ ] **Step 2: Implement**
  - `LeaseLine` entity + repository (`findByLease_IdOrderBySeqNoAsc`, `deleteByLease_Id`).
  - `Lease`: new fields; `@PrePersist` sets `chainId = id` is impossible before id generation → set in `createDraftLease` after first save (`if (chainId == null) chainId = id; save`).
  - `LeaseService.createDraftLease`: keep unit/renter checks; set header from DTO (`contractDate` default = `agreementDate` ?: today; `firstDueDate` default = `startDate`); save; `applyLines`; `syncDerivedTotals`; **no schedule generation here** (cheques are generated explicitly in Task 5); record event; publish `LEASE_CREATED`.
  - `applyLines`: validates ≥ 1 line, each amount ≥ 0, discount ≤ gross, charge type active; RENT lines get `periodStart/End` defaulted to lease start/end; resolves default credit account with a `try { resolver.resolve(role, propertyId) } catch (UnmappedAccountRoleException e) { /* leave null */ }`.
  - `updateDraftLease`: DRAFT only; header + `applyLines` + `syncDerivedTotals`; **deletes DRAFT cheques** for the lease (`chequeRepository.deleteAll(findByLease_Id… where status = DRAFT)`) because amounts may have changed — the UI regenerates them.
  - `deleteDraftLease`: also delete `lease_lines` (cascade FK handles it) and DRAFT cheques.
  - `mapToDTO`: fill new fields; `lines` via `LeaseLineRepository`.
  - Remove `syncCharges`, `createOneTimeChargeAndDepositRows`, `totalRentFor`, `addBookingDeposit`, `updatePaymentSchedule` and their DTOs' usages in `LeaseController` (the endpoint `PUT /{id}/payment-schedule` is deleted; `POST /{id}/cheques` replaces it in Task 5).
  - `Property.code` + `PropertyService` create/update accept it; `PropertyDTO`/entity binding exposes `code`.
  - `LeaseStatus.RENEWED` added; `LeaseExpirationJob` unchanged for now.

- [ ] **Step 3: Fix compile fallout in callers** (`ContractGenerationService` reads `lease.getMonthlyRent()` → replace with `lease.getRentAmount()` divided by months only where it prints a monthly figure; `PortfolioImportPersistService` — temporarily build `LeaseLineInput` list from its `Leases` sheet (rent → RENT, deposit → SECURITY_DEPOSIT, admin fee column → ADMIN_FEE) and stop creating schedules; `RenewalOpportunityService`, `UnitListingService.syncAvailableFrom` untouched).

- [ ] **Step 4: Run** `./gradlew test --tests '*LeaseLinesIT' --tests '*LeaseService*'` → PASS (delete `LeaseChargesGenerationIT`, `LeaseServicePaymentScheduleValidationTest`, `LeaseTermMonthCountIT` — they test removed behaviour; keep `LeaseServiceUnitOccupancyTest`, adapting fixtures to lines).

- [ ] **Step 5: Spec note + commit** — in the spec §6.3 change "Removed: `rent_amount`, `monthly_rent`, `deposit_amount`…" to "`rent_amount` and `deposit_amount` are kept as derived mirrors of the RENT/DEPOSIT lines (read-only); `monthly_rent` is removed." Commit `feat(leasing): lease lines with charge types, contract header fields, derived totals`.

---

### Task 5: Cheque generation (grid rows on a draft lease), cheque numbers, row editing

**Files:**
- Create: `core/service/lease/ChequeGenerationService.java`, `api/dto/lease/GenerateChequesRequest.java`, `api/dto/lease/ChequeRowInput.java`
- Modify: `core/service/ChequeRoundingCalculator.java` (+ step 10; `distribute(total, n, strategy, minStep)`), `api/LeaseController.java`
- Test: `core/service/lease/ChequeGenerationServiceTest.java` (unit), `core/service/lease/ChequeGenerationServiceIT.java`

**Interfaces:**
- `record GenerateChequesRequest(Integer installments, LocalDate firstDueDate, InstallmentDistribution distribution, String payeeBank, UUID debitAccountId, boolean foldDepositsAndFeesIntoFirst, ChequeMode mode)` — defaults: `installments` = lease.paymentTerms, `firstDueDate` = lease.firstDueDate, distribution = `FIRST_LARGER`, mode = PDC, fold = true.
- `record ChequeRowInput(UUID id, Integer seqNo, LocalDate postingDate, String chequeNumber, LocalDate chequeDate, String payeeBank, String payerName, UUID debitAccountId, BigDecimal amount, String narration, ChequeMode mode)`.
- `ChequeGenerationService.generate(UUID leaseId, GenerateChequesRequest r) : List<ChequeDTO>` — DRAFT lease only; deletes existing DRAFT rows; rent = Σ RENT lines net split by `ChequeRoundingCalculator.distribute(rent, n, distribution, TEN)`; non-rent lines (DEPOSIT + FEE) folded into row 1 when `fold` else emitted as their own rows dated `contractDate`; due dates = `firstDueDate.plusMonths(floor(i × months / n))` (same spacing rule as today); narration `"Rent - 1st Installment"` … (`"Rent - 1st Installment | SD | Admin"` when folded, matching PACT); `postingDate = contractDate`; `debitAccountId` default = property `BANK` mapping.
- `ChequeGenerationService.generateNumbers(UUID leaseId, String startingNumber) : List<ChequeDTO>` — fills `chequeNumber` sequentially over DRAFT PDC rows ordered by `seqNo`, preserving zero-padding width (`"100040"` → `100041`…).
- `ChequeGenerationService.saveRows(UUID leaseId, List<ChequeRowInput> rows) : List<ChequeDTO>` — replace the DRAFT grid with the given rows (upsert by id), validate: amounts > 0, PDC rows have a `chequeDate`, CASH/TRANSFER rows too (= expected receipt date).
- `ChequeRoundingCalculator`: `STEPS` becomes `[1000, 500, 100, 10, 0.01]`; new overload `distribute(BigDecimal total, int n, InstallmentDistribution strategy, BigDecimal minStep)` starts from `minStep` downward (PACT-style tens). Existing 3-arg overload unchanged.
- Endpoints (`LeaseController`, roles SA/TA/ACCOUNTANT/PM): `POST /api/v1/leases/{id}/cheques/generate` body `GenerateChequesRequest` → `List<ChequeDTO>`; `POST /api/v1/leases/{id}/cheques/numbers` body `{startingNumber}` → `List<ChequeDTO>`; `PUT /api/v1/leases/{id}/cheques` body `List<ChequeRowInput>` → `List<ChequeDTO>`; `GET /api/v1/leases/{id}/cheques` → `List<ChequeDTO>` (any status).

- [ ] **Step 1: Failing unit test for the PACT split**

```java
class ChequeGenerationServiceTest {
    @Test void pactExampleSixChequesRoundedToTensFirstAbsorbs() {
        var r = ChequeRoundingCalculator.distribute(new BigDecimal("61000"), 6, InstallmentDistribution.FIRST_LARGER, new BigDecimal("10"));
        assertThat(r.amounts()).containsExactly(bd("10200"), bd("10160"), bd("10160"), bd("10160"), bd("10160"), bd("10160"));
    }
    @Test void galahFourChequesUniformWhenDivisible() {
        var r = ChequeRoundingCalculator.distribute(new BigDecimal("51000"), 4, InstallmentDistribution.FIRST_LARGER, new BigDecimal("10"));
        assertThat(r.amounts()).containsExactly(bd("12750"), bd("12750"), bd("12750"), bd("12750"));
    }
    @Test void foldingDepositsAndFeesIntoFirstRowMatchesPact() {
        // rent 61000 / SD 3000 / admin 500, 6 cheques, fold=true -> row1 = 13700 "Rent - 1st Installment | SD | Admin", rows 2..6 = 10160
        List<ChequeGenerationService.Row> rows = ChequeGenerationService.buildRows(
            bd("61000"), List.of(new ChequeGenerationService.Extra("SD", bd("3000")), new ChequeGenerationService.Extra("Admin", bd("500"))),
            6, LocalDate.of(2026,9,11), LocalDate.of(2026,9,11), LocalDate.of(2027,9,10), InstallmentDistribution.FIRST_LARGER, true);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("13700");
        assertThat(rows.get(0).narration()).isEqualTo("Rent - 1st Installment | SD | Admin");
        assertThat(rows.get(1).amount()).isEqualByComparingTo("10160");
        assertThat(rows.get(1).narration()).isEqualTo("Rent - 2nd Installment");
        assertThat(rows.get(5).chequeDate()).isEqualTo(LocalDate.of(2027,7,11)); // 11 Sep + floor(5*12/6)=10 months
        assertThat(rows.stream().map(ChequeGenerationService.Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("64500");
    }
    @Test void numbersFillSequentiallyPreservingWidth() {
        assertThat(ChequeGenerationService.nextNumber("100040", 3)).isEqualTo("100043");
        assertThat(ChequeGenerationService.nextNumber("000028", 1)).isEqualTo("000029");
    }
}
```
`buildRows` and `nextNumber` are pure `static` helpers on the service; `Row(int seqNo, LocalDate postingDate, LocalDate chequeDate, BigDecimal amount, String narration)`, `Extra(String label, BigDecimal amount)`.

- [ ] **Step 2: Implement** the calculator overload, the static helpers, the service (transactional; DRAFT-only guard; deletes DRAFT rows; persists `Cheque` rows with `status = DRAFT`, `renter/property/unit` from the lease, `debitAccount` from the property BANK mapping when not given — if unmapped leave null and let the post guard report `BANK`), and the four endpoints.

- [ ] **Step 3: IT** — generate on the Task 4 draft (51,000 + 2,000 + 3,000, 4 cheques, fold) → 4 rows, row 1 = 17,750 (12,750 + 3,000 + 2,000), Σ = 56,000; `generateNumbers("100040")` → 100040..100043; `saveRows` with an amount edited so Σ ≠ contract value is **allowed** here (the mismatch is reported at post time, Task 6); generating on an ACTIVE lease → 400.

- [ ] **Step 4: Run, commit** `feat(cheques): cheque grid generation with PACT rounding, numbering, row editing`.

---

### Task 6: LeasePostingService — post, validation guard, amend lines (TCR + re-post), unit claim, events

**Files:**
- Create: `core/service/lease/LeasePostingService.java`, `core/service/lease/LeasePostedEvent.java`, `core/service/lease/LeaseAmendedEvent.java`, `api/dto/lease/PostLeaseResponse.java`
- Modify: `api/LeaseController.java` (`POST /{id}/post`, `POST /{id}/amend-lines`; `PUT /{id}/activate` removed), `core/service/LeaseService.java` (`claimUnitForLease` made package-visible, `acceptLease` no longer activates — it moves PENDING_SIGNATURE → DRAFT-with-accepted flag? **No:** accept keeps status PENDING_SIGNATURE and sets `renterAcceptedAt`; Post is the only path to ACTIVE)
- Test: `core/service/lease/LeasePostingServiceIT.java`

**Interfaces:**
```java
public record LeasePostedEvent(UUID tenantId, UUID leaseId, LocalDate contractDate) {}
public record LeaseAmendedEvent(UUID tenantId, UUID leaseId, UUID reversedJournalId, UUID newJournalId) {}
public record PostLeaseResponse(LeaseDTO lease, UUID tcoJournalId, String tcoEntryNumber, List<ChequeDTO> cheques) {}

@Transactional PostLeaseResponse LeasePostingService.post(UUID leaseId)
@Transactional PostLeaseResponse LeasePostingService.amendLines(UUID leaseId, List<LeaseLineInput> newLines, String reason)
Set<AccountRole>            LeasePostingService.requiredRoles(Lease lease)   // lines' roles + RENT_RECEIVABLE + PDC_RECEIVABLE + BANK (+ OUTPUT_VAT when any line vatApplicable)
```
Consumes: `PostingService.post` with `PostingRequest.ofPairs` (Plan 1 Addendum A), `AccountResolver.resolveAll`, `ChequeRepository`, `LeaseLineRepository`, `TenantFiscalSettingsService` (lock check happens inside PostingService).

Posting rules (spec §6.4) — `entryDate = lease.contractDate`, header dims `(propertyId, unitId, leaseId, renterId, null)`:
- `TCO`: for each line in `seqNo` order: `pair(dr(RENT_RECEIVABLE, net).withNarration(chargeType.nameEn), cr(ById(line.creditAccountId), net))`; if `line.vatApplicable`: additional `pair(dr(RENT_RECEIVABLE, vat), cr(OUTPUT_VAT, vat))` with `vat = net × 0.05` HALF_UP 2dp, narration `"VAT on " + chargeType.nameEn`. Rent-receivable role resolved once; if `lease.receivableAccountId` is set use `ById(receivableAccountId)` instead of the role.
- `PDR` per cheque row (all modes): `pair(dr(PDC_RECEIVABLE, amount), cr(RENT_RECEIVABLE, amount))`, `entryDate = row.postingDate`, dims + `chequeId`, narration = row narration, `sourceType = CHEQUE, sourceId = cheque.id`; row → `REGISTERED`, `pdrJournalId` set.
- Validation before any posting: status ∈ {DRAFT, PENDING_SIGNATURE}; ≥ 1 line; every line has `creditAccountId` (else list them: "Line 2 (Admin Fee) has no credit account"); `resolveAll(requiredRoles, propertyId)` (throws `UnmappedAccountRoleException` listing all); Σ cheque amounts == contract value (else `"Cheque grid totals 53,000.00 but contract value is 56,000.00"`); every PDC row has `chequeDate`; `contractDate` not null.
- After posting: `lease.postingJournalId/postedAt/postedBy`, status `ACTIVE`, `claimUnitForLease`, `recordEvent(prev, ACTIVE, "Lease posted TCO-26/1629")`, publish `LEASE_ACTIVATED` email event (existing) and `LeasePostedEvent`. If `renewedFromLeaseId != null` → predecessor status `RENEWED` (+ event), unit stays occupied (do **not** vacate).
- `amendLines`: lease must be ACTIVE (or RENEWED? no — ACTIVE only); every cheque must be in `REGISTERED` (else "Cheque 100041 is DEPOSITED; amend is only possible while all cheques are REGISTERED"); reverse the current `TCO` (`PostingService.reverse(postingJournalId, today, reason)` → TCR), replace lines (`LeaseService.applyLines` + `syncDerivedTotals`), post a fresh `TCO`, update `postingJournalId`; cheques untouched (Σ cheques must still equal the new contract value, else reject **before** reversing); publish `LeaseAmendedEvent`.

- [ ] **Step 1: Failing IT** (uses `LeaseTestFixtures`; Galah 2 numbers)

```java
@Test void postWritesTcoWithOnePairPerLineAndOnePdrPerCheque() {
    // draft: RENT 51000 (Cr Advance Rent), ADMIN_FEE 2000; cheques: 2000 admin 11-09-2026 + 4 x 12750 (02-10-2026, 02-01-2027, 02-04-2027, 02-07-2027); contractDate 16-09-2026
    PostLeaseResponse r = posting.post(lease.getId());
    assertThat(r.tcoEntryNumber()).startsWith("TCO-26/");
    List<JournalLine> tco = lines.findByEntry_IdOrderByLineNoAsc(r.tcoJournalId());
    assertThat(tco).hasSize(4); // Dr RR 51000 / Cr Advance 51000 / Dr RR 2000 / Cr Admin 2000
    assertThat(tco.get(0).getContraAccountId()).isEqualTo(advanceRentLeaf.getId());
    assertThat(r.cheques()).allMatch(c -> c.status() == ChequeStatus.REGISTERED && c.pdrJournalId() != null);
    JournalEntry pdr1 = entries.findById(r.cheques().get(0).pdrJournalId()).orElseThrow();
    assertThat(pdr1.getDocType()).isEqualTo(JournalDocType.PDR);
    assertThat(pdr1.getEntryDate()).isEqualTo(LocalDate.of(2026, 9, 16)); // posting date = contract date
    // renter ledger nets to zero on Rent Receivable
    AccountLedgerDTO rr = ledger.accountLedger(rentReceivableLeaf.getId(), new LedgerFilter(null, null, null, null, lease.getId(), null));
    assertThat(rr.closingBalance()).isEqualByComparingTo("0");
    assertThat(leaseRepo.findById(lease.getId()).orElseThrow().getStatus()).isEqualTo(LeaseStatus.ACTIVE);
    assertThat(unitRepo.findById(unit.getId()).orElseThrow().getStatus()).isEqualTo(UnitStatus.OCCUPIED);
}
@Test void postRejectsWhenChequesDoNotSumToContractValue() { /* drop one cheque -> BusinessRuleViolationException containing "Cheque grid totals" and NO journals written */ }
@Test void postRejectsUnmappedRolesListingAllOfThem() { /* clear property PDC_RECEIVABLE + BANK mappings -> UnmappedAccountRoleException mentioning both */ }
@Test void postRejectsLineWithoutCreditAccount() { /* create ADMIN_FEE line with creditAccountId null and no ADMIN_FEE mapping -> message "has no credit account" */ }
@Test void amendLinesReversesAndRepostsWhenAllChequesRegistered() {
    // post; then amendLines(rent 50000 + admin 2000 + new line? keep cheques sum = 53000 -> reject; then rent 51000 admin 2000 discount 1000 on rent... -> contract 52000 mismatch reject)
    // valid amend: swap admin fee credit account to a different leaf, same amounts -> TCR + new TCO; old TCO status REVERSED; lease.postingJournalId == new id
}
@Test void amendLinesRejectedOnceAChequeIsDeposited() { /* deposit via repo status change -> 400 mentioning cheque number */ }
@Test void vatLinePostsOutputVatPair() { /* ADMIN_FEE 2000 vatApplicable -> extra pair Dr RR 100 / Cr OUTPUT_VAT 100; cheques must total 53100 */ }
@Test void postingIntoLockedPeriodIsRejected() { /* fiscal.lockThrough(2026-09-30); contractDate 16-09-2026 -> "locked through" */ }
```

- [ ] **Step 2: Implement** per Interfaces. `LeaseController`: `POST /{id}/post` (SA/TA/ACCOUNTANT) → `PostLeaseResponse`; `POST /{id}/amend-lines` body `{lines: LeaseLineInput[], reason}` (SA/TA/ACCOUNTANT); delete `PUT /{id}/activate`. `acceptLease`: keep the renter flow but it no longer changes status to ACTIVE; add `leases.renter_accepted_at` in changeset 83? — it is not there; add it to changeset 83 now (`timestamptz`) since 83 is unreleased on this branch (append a column to the `addColumn` block for `leases`).

- [ ] **Step 3: Run, commit** `feat(leasing): post lease as TCO + PDR journals, amend by reversal, unit claim`.

---

### Task 7: ChequeService — lifecycle with one journal per transition; due rules

**Files:**
- Create: `core/service/cheque/ChequeService.java`, `core/service/cheque/ChequeDueRules.java`, `api/dto/cheque/ChequeActionRequest.java`, `ReplaceChequeRequest.java`, `DepositBatchRequest.java`
- Test: `core/service/cheque/ChequeServiceIT.java`

**Interfaces:**
```java
record ChequeActionRequest(LocalDate date, String notes, ChequeFailureReason failureReason, UUID debitAccountId)  // date defaults today
record DepositBatchRequest(List<UUID> chequeIds, LocalDate date, UUID debitAccountId)                                // debitAccountId optional override
record ReplaceChequeRequest(List<ChequeRowInput> replacements, LocalDate date, String notes)                        // one or more rows

ChequeDTO   deposit(UUID chequeId, ChequeActionRequest r)          // REGISTERED(PDC) -> DEPOSITED, no journal
List<ChequeDTO> depositBatch(DepositBatchRequest r)
ChequeDTO   clear(UUID chequeId, ChequeActionRequest r)            // DEPOSITED -> CLEARED: CRT Dr debitAccount / Cr PDC_RECEIVABLE
ChequeDTO   receive(UUID chequeId, ChequeActionRequest r)          // REGISTERED(CASH|TRANSFER) -> CLEARED: CRT Dr debitAccount|CASH / Cr PDC_RECEIVABLE
ChequeDTO   bounce(UUID chequeId, ChequeActionRequest r)           // DEPOSITED -> BOUNCED: CBR Dr RENT_RECEIVABLE / Cr PDC_RECEIVABLE ; CLEARED -> BOUNCED: CBR Dr RENT_RECEIVABLE / Cr debitAccount
List<ChequeDTO> replace(UUID chequeId, ReplaceChequeRequest r)     // BOUNCED -> REPLACED; each new row REGISTERED with its own PDR
ChequeDTO   cancel(UUID chequeId, ChequeActionRequest r)           // REGISTERED -> CANCELLED: reversal of PDR
ChequeDTO   returnToTenant(UUID chequeId, LocalDate date, String reason)   // REGISTERED|DEPOSITED -> RETURNED: reversal of PDR  (Plan 3 termination)
ChequeDTO   addRowToPostedLease(UUID leaseId, ChequeRowInput row)  // posts PDR immediately, REGISTERED (used by replace, penalties, extension, cash receipt)
ChequeDTO   registerOnlinePending(UUID chequeId) / revertOnlinePending(UUID chequeId)   // Task 10
```
All transitions: lock via `findByIdForUpdate` (translate `PessimisticLockingFailureException` → 400 "being updated by another request"), assert `from` status with message `"Can only <verb> cheques in <STATUS> (current: X)"`, set `status`, `statusChangedAt`, the date column, journal id, save, then side effects (`EmailEvent` `CHEQUE_DEPOSITED` / `CHEQUE_RECEIVED` on register / `PAYMENT_BOUNCED` + in-app notification as today), then `PenaltyRuleEngine.onBounce(cheque)` (Task 9) inside the same transaction.
`ChequeDueRules`: `static boolean due(Cheque c, LocalDate today)`, `overdue(Cheque, int grace, LocalDate today)`, `daysOverdue(...)` — spec §7.5.
Journal dims for every posting: `(propertyId, unitId, leaseId, renterId, chequeId)`; `sourceType = CHEQUE`, `sourceId = chequeId`; narration = cheque narration; pairs via `ofPairs`.

- [ ] **Step 1: Failing IT** — one test per row of the spec §7.2 table, each asserting `docType`, `entryDate`, the two accounts (`contraAccountId`) and amounts, plus: `bounceAfterClearDebitsBankNotPdc`; `replaceWithTwoRowsPostsTwoPdrsAndResidualStaysInReceivable` (bounced 12,750 replaced by 10,000 + 2,000 → RR ledger balance for the lease = 750); `cancelReversesPdr`; `receiveCashUsesCashRoleWhenNoDebitAccount`; `illegalTransitionIs400` (clear a REGISTERED cheque); `concurrentClearAndBounceOneWins` (two threads, one `BusinessRuleViolationException`).

- [ ] **Step 2: Implement** service + rules; `ChequeController` is Task 11 — for now expose through the IT only.

- [ ] **Step 3: Run, commit** `feat(cheques): register lifecycle with CRT/CBR/PDR-reversal journals per transition`.

---

### Task 8: Renew (chain) and Extend (additive)

**Files:**
- Create: `core/service/lease/LeaseRenewalService.java`, `api/dto/lease/RenewLeaseRequest.java`, `api/dto/lease/ExtendLeaseRequest.java`
- Modify: `api/LeaseController.java` (`POST /{id}/renew`, `POST /{id}/extend` replaces `ExtendLeaseDTO` path), `core/service/LeaseService.java` (remove `extendLease`), `core/service/renewal/RenewalOpportunityService.markRenewed` (also called from renew-post)
- Test: `core/service/lease/LeaseRenewalServiceIT.java`

**Interfaces:**
- `record RenewLeaseRequest(LocalDate contractDate, LocalDate startDate, LocalDate endDate, List<LeaseLineInput> lines, boolean carryDepositForward)` — `lines` null ⇒ copy predecessor's lines (amounts editable later in DRAFT).
- `LeaseRenewalService.renew(UUID leaseId, RenewLeaseRequest r) : LeaseDTO` — predecessor must be ACTIVE/EXPIRED/NOTICE_GIVEN; creates DRAFT with `renewedFromLeaseId`, `chainId = predecessor.chainId`, same unit/renter; **unit vacancy check is skipped** for a renewal of the unit's current lease; when `carryDepositForward`: no DEPOSIT line is copied and a flag `carryDepositForward` is stored on the new lease (column added to 83: `carry_deposit_forward boolean default false`) — on **post** of the successor, `LeasePostingService` posts a `JV` `pair(dr(SECURITY_DEPOSIT, amount).withDims(old lease dims), cr(SECURITY_DEPOSIT, amount).withDims(new lease dims))` for the predecessor's deposit balance and marks predecessor `RENEWED`.
- `record ExtendLeaseRequest(LocalDate newEndDate, LocalDate contractDate, List<LeaseLineInput> lines, List<ChequeRowInput> cheques)` — lines must be RENT (period = old end + 1 → new end) or FEE; cheques Σ must equal Σ new lines net.
- `LeaseRenewalService.extend(UUID leaseId, ExtendLeaseRequest r) : PostLeaseResponse` — ACTIVE only; appends lines (seqNo continues) and cheque rows (status DRAFT → registered by the post below); posts a further `TCO` (narration "Extension to <newEndDate>") + `PDR`s dated `r.contractDate`; sets `endDate`, recomputes `totalDays`, derived totals; publishes `LeaseExtendedEvent(tenantId, leaseId, previousEndDate, newEndDate, lineIds)` (Plan 3 adds a segment).

- [ ] **Step 1: Failing IT** — `renewCopiesLinesIntoDraftWithChain`; `postingSuccessorMarksPredecessorRenewedAndKeepsUnitOccupied`; `carryDepositForwardPostsJvBetweenLeaseDims`; `extendAppendsLinesChequesAndPostsAdditionalTco` (asserts the second TCO's lines and that the original TCO is still POSTED, not reversed); `extendRejectsChequeMismatch`.
- [ ] **Step 2: Implement.** `LeaseController`: `POST /{id}/renew` (SA/TA/ACCOUNTANT/PM → DRAFT), `POST /{id}/extend` (SA/TA/ACCOUNTANT). Delete `LeaseService.extendLease` and `ExtendLeaseDTO`.
- [ ] **Step 3: Run, commit** `feat(leasing): renewal chain with RENEWED status and additive extension posting`.

---

### Task 9: Penalty assessments — propose by rule, approve → PEN + collection row, waive, reverse

**Files:**
- Create: `domain/entity/enums/PenaltyReason.java`, `PenaltyAssessmentStatus.java`, `domain/entity/PenaltyAssessment.java`, `domain/repository/PenaltyAssessmentRepository.java`, `core/service/penalty/PenaltyAssessmentService.java`, `core/service/penalty/PenaltyRuleEngine.java`, `api/PenaltyAssessmentController.java`, `api/dto/penalty/PenaltyAssessmentDTO.java`, `ProposePenaltyRequest.java`
- Modify: `core/service/FineConfig.java` (+`bouncesBeforePenalty`, `autoProposeChequeReturn`, `autoProposeLatePayment`), `FineConfigResolver.java`, `LandlordOrgFineSettings.java`, `RentCollectionSettings.java` (+`bouncesBeforePenalty`), `FineSettingsController`/DTO (expose the three new fields), `core/service/cheque/ChequeService.java` (call `ruleEngine.onBounce` / `onLateClear`)
- Test: `core/service/penalty/PenaltyAssessmentServiceIT.java`, `PenaltyRuleEngineTest.java`

**Interfaces:**
```java
enum PenaltyReason { CHEQUE_RETURN, LATE_PAYMENT, OTHER }
enum PenaltyAssessmentStatus { PROPOSED, APPROVED, WAIVED, REVERSED }
record ProposePenaltyRequest(UUID leaseId, UUID chequeId, PenaltyReason reason, BigDecimal amount, String description)
record PenaltyAssessmentDTO(UUID id, UUID leaseId, UUID chequeId, String chequeNumber, UUID renterId, String renterName, UUID propertyId, String propertyName,
        PenaltyReason reason, BigDecimal amount, String description, PenaltyAssessmentStatus status, UUID proposedBy, Instant proposedAt,
        UUID approvedBy, Instant approvedAt, UUID journalId, UUID collectionChequeId, ChequeStatus collectionStatus, String resolutionNote)

PenaltyAssessmentDTO propose(ProposePenaltyRequest r, UUID byUser /* null = SYSTEM */)
PenaltyAssessmentDTO approve(UUID id, LocalDate date)         // PROPOSED -> APPROVED: PEN pair(dr(RENT_RECEIVABLE), cr(role by reason)) + addRowToPostedLease(mode CASH, chequeDate = date, narration "Penalty - <reason>", penaltyAssessmentId)
PenaltyAssessmentDTO waive(UUID id, String note)              // PROPOSED -> WAIVED, nothing posted
PenaltyAssessmentDTO reverse(UUID id, LocalDate date, String note) // APPROVED -> REVERSED: PostingService.reverse(journalId) + cancel the collection row if still REGISTERED (its PDR reversed)
Page<PenaltyAssessmentDTO> list(UUID leaseId, PenaltyAssessmentStatus status, UUID propertyId, Pageable p)
List<PenaltyAssessmentDTO> forRenter(UUID renterId)          // APPROVED only
```
Role by reason: `CHEQUE_RETURN → CHEQUE_RETURN_PENALTY`, `LATE_PAYMENT → RENT_PENALTY`, `OTHER → OTHER_INCOME`.
`PenaltyRuleEngine.onBounce(Cheque c)`: `cfg = fineConfigResolver.resolve(propertyId, tenantId)`; `bounces = chequeRepository.countByLease_IdAndBouncedAtIsNotNull(leaseId)`; if `cfg.autoProposeChequeReturn() && bounces >= cfg.bouncesBeforePenalty()` and no PROPOSED/APPROVED CHEQUE_RETURN assessment exists for this cheque → `propose(CHEQUE_RETURN, cfg.amountFor(c.failureReason), "Cheque <no> returned (<reason>), bounce #<n> on this lease")`. `onLateClear(Cheque c, LocalDate clearedOn)`: if `cfg.autoProposeLatePayment()` and `RentCollectionSettings.penaltyType != NONE` and `clearedOn > chequeDate + grace` → propose `LATE_PAYMENT` with `PenaltyCalculationService`-style amount (fixed-per-day × days or % × days) — move that arithmetic into `PenaltyRuleEngine.lateAmount(amount, settings, daysLate)`.
Endpoints (`/api/v1/penalties`): `GET` (SA/TA/ACCOUNTANT/PM; RENTER sees own APPROVED via `forRenter`), `POST` propose (SA/TA/ACCOUNTANT/PM), `POST /{id}/approve`, `/{id}/waive`, `/{id}/reverse` (SA/TA/ACCOUNTANT).

- [ ] **Step 1: Failing tests** — rule engine unit: below threshold → no proposal; at threshold → one proposal with the configured bounce amount; duplicate bounce of the same cheque → no second proposal. Service IT: approve posts `PEN` with correct pair and creates a REGISTERED CASH row linked by `penaltyAssessmentId` whose PDR nets the receivable back to zero; waive posts nothing; reverse posts the mirror and cancels the row; approving twice → 400; renter listing hides PROPOSED.
- [ ] **Step 2: Implement.** Fine settings fields flow through the existing fines settings endpoint + web page (`settings/fines/page.tsx`: add "Bounces before penalty", "Auto-propose cheque-return penalty", "Auto-propose late-payment penalty").
- [ ] **Step 3: Run, commit** `feat(penalties): approval-gated penalty assessments posting PEN with a collection row`.

---

### Task 10: Online payments, receipts and renter "my payments" on the register

**Files:**
- Modify: `domain/entity/OnlinePayment.java` (`paymentSchedule` → `cheque`), `OnlinePaymentRepository` (`findByCheque_Id`), `core/service/OnlinePaymentService.java`, `WebhookService.java`, `api/OnlinePaymentController.java`, `core/service/RentReceiptService.java`, `api/dto/RenterChequeDTO.java` (new, replaces `RenterPaymentScheduleDTO`), `TenantGatewayConfig.java` (+`settlementAccount`), `TenantGatewayConfigController`/DTO (+`settlementAccountId`), email payloads (`OnlinePaymentPayload`, `RentReceiptPayload` take `chequeId`)
- Test: `core/service/OnlinePaymentServiceIT.java` (replace `OnlinePaymentServiceClearIdempotencyIT`)

**Interfaces:**
- `OnlinePaymentService.getMyPayments(userId) : List<RenterChequeDTO>` — every cheque of the renter's leases except REPLACED/CANCELLED/RETURNED/DRAFT, with `due/overdue/daysOverdue` from `ChequeDueRules`, `penaltyOutstanding` = Σ APPROVED assessments' uncleared collection rows for the lease, `payable = due ? amount : 0`, plus the approved-penalty rows themselves (they *are* cheques in mode CASH).
- `createOrder(chequeId)`: allowed when `ChequeDueRules.due(c)` and status ∈ {REGISTERED, BOUNCED}; for BOUNCED, first `chequeService.replace(chequeId, one ONLINE row same amount, today)` and use the replacement; row → `ONLINE_PENDING` (`registerOnlinePending`); amount = `cheque.amount` (penalties are separate rows now — no add-on).
- `verifyPayment` / `clearPaymentFromWebhook`: on capture → `chequeService.clearOnline(chequeId, capturedOn)` = `CRT pair(dr(ById(config.settlementAccountId ?: BANK role), amount), cr(PDC_RECEIVABLE, amount))`, status CLEARED, `paymentMethod` irrelevant (mode is ONLINE). Idempotent on already-CLEARED. Signature failure / cancel → `revertOnlinePending` (back to REGISTERED).
- `RentReceiptService.generateReceipt(chequeId)`: CLEARED only; receipt number `RR-YYYY-MM-<id8>` from `clearedAt`; reads `chequeNumber, payeeBank, mode, amount, narration`.
- `GET /api/v1/cheques/{id}/receipt` (SA/TA/ACCOUNTANT/PM/RENTER; renter must own the lease via `LeaseAccessPolicy`).

- [ ] **Step 1: Failing IT** — `createOrderOnDueRegisteredChequeMovesItToOnlinePending`; `captureClearsViaCrtToSettlementAccount`; `duplicateWebhookIsNoop`; `cancelRevertsToRegistered`; `bouncedChequePaidOnlineGetsOnlineReplacementRow`; `receiptOnlyForCleared`.
- [ ] **Step 2: Implement; delete `RenterPaymentScheduleDTO`. Commit** `feat(payments): online receipts clear register rows via CRT; receipts on cheques`.

---

### Task 11: Cheque API + register queries; rewire every remaining PaymentSchedule reader

**Files:**
- Create: `api/ChequeController.java`, `core/service/cheque/ChequeQueryService.java`, `api/dto/cheque/ChequeSummaryDTO.java`, `api/dto/cheque/AgingReportDTO.java` (rebuilt), `api/dto/cheque/LeaseChequeStatsDTO.java`
- Modify: `NotificationScheduler.java`, `NotificationService.java` (`sendPenaltyIncurred` → takes `PenaltyAssessment`), `DashboardService.java`, `ContractGenerationService.java` (`buildSection4Rows(List<Cheque>)`), `MeetingService.java` + `MeetingDetail.java` (`chequeIds`), `LeaseExpirationJob.java` (uncleared rows → `chequeService.cancel` with reversal), `cheque/ChequeImageRetentionJob.java`, `cheque/ChequeExtractionService.java` + `LeaseController` bulk-attach (targets DRAFT/REGISTERED cheques; sets number/bank/date/image), `LeaseAccessPolicy.java` (`ROLE_ACCOUNTANT` sees all), `PortfolioImportPersistService.java` (cheques sheet → `ChequeRowInput`s on the draft), `api/LeaseController.java` (`GET /{id}/cheques` moved here)
- Test: `api/ChequeControllerIT.java`, `core/service/cheque/ChequeQueryServiceIT.java`

**Interfaces — `ChequeController` (`/api/v1/cheques`, SA/TA/ACCOUNTANT/PM unless noted):**
| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `` | `?propertyId&status&mode&from&to&search&page&size` | `Page<ChequeDTO>` |
| GET | `/due` | `?propertyId&page&size` | `Page<ChequeDTO>` (register `due` predicate) |
| GET | `/to-deposit` | `?propertyId&page&size` | `Page<ChequeDTO>` |
| GET | `/post-dated` | `?propertyId&month=YYYY-MM` | `List<ChequeDTO>` REGISTERED/DEPOSITED by maturity |
| GET | `/summary` | `?propertyId` | `ChequeSummaryDTO(registeredCount, registeredAmount, depositedCount, depositedAmount, clearedThisMonthAmount, bouncedCount, bouncedAmount, dueCount, dueAmount, overdueCount, overdueAmount)` |
| GET | `/aging` | `?propertyId` | `AgingReportDTO` buckets over `due` rows by `daysOverdue` (current, 1–30, 31–60, 61–90, 90+) |
| POST | `/stats-by-leases` | `List<UUID>` | `List<LeaseChequeStatsDTO(leaseId, total, cleared, uncleared, bounced, totalAmount, clearedAmount, dueAmount)>` |
| GET | `/{id}` | | `ChequeDTO` |
| PUT | `/{id}/deposit` | `ChequeActionRequest` | `ChequeDTO` |
| POST | `/deposit-batch` | `DepositBatchRequest` | `List<ChequeDTO>` |
| PUT | `/{id}/clear` | `ChequeActionRequest` | `ChequeDTO` |
| PUT | `/{id}/receive` | `ChequeActionRequest` | `ChequeDTO` |
| PUT | `/{id}/bounce` | `ChequeActionRequest` (failureReason required) | `ChequeDTO` |
| POST | `/{id}/replace` | `ReplaceChequeRequest` | `List<ChequeDTO>` |
| PUT | `/{id}/cancel` | `ChequeActionRequest` | `ChequeDTO` (SA/TA/ACCOUNTANT) |
| PUT | `/{id}/details` | `ChequeRowInput` (number/bank/date/image only) | `ChequeDTO` — REGISTERED only, no journal |
| POST | `/lease/{leaseId}/cash-receipt` | `ChequeRowInput` (mode CASH/TRANSFER) | `ChequeDTO` — PACT "Cash Receipt Voucher – Rent": `addRowToPostedLease` + `receive` in one call |
| GET | `/{id}/receipt` | | PDF (Task 10) |

`ChequeQueryService` implements summary/aging/stats with JPQL over `cheques` (no `findAll()` scans). `DashboardService`: "collected this month" = `sumClearedBetween`; "overdue" card = `findDue` count with overdue predicate; monthly chart = cheques grouped by `cheque_date` month (expected) vs CLEARED (collected). `NotificationScheduler`: reminders over `findByStatusInAndChequeDateBetween(REGISTERED, today+3..today+3)`; overdue pass over `findDue`.

- [ ] **Step 1: Failing HTTP IT** — happy path deposit → clear via HTTP with `X-*` headers; PM allowed to deposit/clear; PM forbidden to cancel; summary counts after the Task 7 scenario; bulk attach writes number/bank/date onto REGISTERED rows and rejects a DEPOSITED one with the existing error-row shape.
- [ ] **Step 2: Implement** controller + query service; rewire each reader listed in Files (mechanical: replace `PaymentSchedule` with `Cheque`, `dueDate` → `chequeDate`, `installmentNumber` → `seqNo`, `purposeLabel` → `narration`, flags → `mode`/`narration`); `MeetingDetail.chequeIds` mapped to the new `uuid[]` column, old column ignored until 84 drops it.
- [ ] **Step 3: Run the affected suites, commit** `feat(cheques): cheque API, register queries, dashboards/notifications/contracts on the register`.

---

### Task 12: Changeset 84 — drop v1 schedules/charges/penalties; delete v1 code and tests

**Files:**
- Create: `db/changelog/changesets/84-drop-v1-schedules.yaml`; append include
- Delete: everything listed under **Backend — deleted** in File structure, plus tests: `PaymentSchedule*Test/IT`, `PaymentScheduleRepository*Test`, `ChequeRoundingCalculatorTest` (rewrite as `ChequeRoundingCalculatorTest` covering the new overload — keep the file, rewrite the cases), `PaymentScheduleControllerDepositTest`, `PaymentScheduleControllerOverdueTest`, `ChequeFailurePenaltyIT`, `PenaltyClearanceIT`, `PenaltyPaymentServiceTest`, `PenaltyServiceChequeFailureAccrualTest`, `PenaltyServiceSettlementTotalTest`, `PenaltyServiceWaiveTest`, `NotificationServicePenaltyHelpersTest`, `PenaltyControllerTest`, `SettlementDepositLedgerTest`, `LeaseChequeBulkAttachControllerTest` (rewrite against cheques), `PortfolioImport*` (rewrite the lease/cheque assertions), `PayloadVarsExtractorChequeNumberTest` (rename fields)
- Modify: `SettlementService.java` — `getSettlementPreview` unpaid rent = Σ `due` cheques (register) until Plan 3 replaces it with the ledger balance; `PenaltyService.getTotalUnwaivedPenalties` → `PenaltyAssessmentService.outstandingForLease` (Σ APPROVED with uncleared collection rows)
- Test: `domain/V1SchedulesRemovedIT.java`

- [ ] **Step 1: Changeset**

```yaml
databaseChangeLog:
  # Accounting v2 plan 2, final step. The cheque register (cheques) replaced
  # payment_schedules; lease_lines replaced lease_charges; penalty_assessments
  # replaced payment_penalties/penalty_payments. Wipe-and-reseed (spec D4).
  - changeSet:
      id: 84-drop-v1-schedules
      author: claude
      changes:
        - sql:
            sql: |
              ALTER TABLE online_payments DROP CONSTRAINT IF EXISTS fk_online_payment_schedule;
              ALTER TABLE online_payments DROP COLUMN IF EXISTS payment_schedule_id;
              ALTER TABLE online_payments ALTER COLUMN cheque_id SET NOT NULL;
              ALTER TABLE meeting_details DROP COLUMN IF EXISTS payment_schedule_ids;
              DROP TABLE IF EXISTS penalty_payments CASCADE;
              DROP TABLE IF EXISTS payment_penalties CASCADE;
              DROP TABLE IF EXISTS payment_schedules CASCADE;
              DROP TABLE IF EXISTS lease_charges CASCADE;
              ALTER TABLE leases DROP COLUMN IF EXISTS monthly_rent;
      rollback:
        - sql:
            # no rollback: v1 schedules are retired (a bare "SELECT 1 -- ..." scalar with ": " does not parse in SnakeYAML)
            sql: "SELECT 1"
```

- [ ] **Step 2: Delete the Java + tests, fix compile until `./gradlew compileJava compileTestJava` is clean.** `V1SchedulesRemovedIT` asserts the four tables are gone and `online_payments.cheque_id` is NOT NULL.
- [ ] **Step 3: Full backend suite → PASS. Commit** `refactor(leasing): retire payment_schedules, lease_charges, payment_penalties`.

---

### Task 13: Web — leasing API client, RBAC, i18n

**Files:**
- Create: `web/src/lib/api/leasing.ts`
- Modify: `web/src/lib/rbac.ts` (`canPostLeases: SA/TA/ACCOUNTANT`, `canManageCheques: SA/TA/ACCOUNTANT/PM`, `canApprovePenalties: SA/TA/ACCOUNTANT`; `canManageLeases` gains ACCOUNTANT), `web/messages/en.json`, `ar.json` (new `Leasing` and `Cheques` namespaces)
- Test: `web/src/lib/api/__tests__/leasing.test.ts`

**Interfaces (`leasing.ts`):** types `ChargeType`, `LeaseLine`, `LeaseLineInput`, `LeaseDetail` (= `LeaseDTO` shape incl. `lines`, `contractValue`, `displayContractNumber`, `chainId`, `renewedFromLeaseId`, `postingJournalId`), `Cheque` (= `ChequeDTO`), `ChequeRowInput`, `GenerateChequesRequest`, `PostLeaseResponse`, `PenaltyAssessment`, `ChequeSummary`, `AgingReport`; and
```ts
export const chargeTypeApi = { list(activeOnly?), create(body), update(id, body) }
export const leaseApi = { get(id), createDraft(body), updateDraft(id, body), post(id), amendLines(id, {lines, reason}), renew(id, body), extend(id, body), cheques(id),
                          generateCheques(id, req), generateChequeNumbers(id, startingNumber), saveCheques(id, rows), journals(id) /* GET /finance/journals?leaseId */ }
export const chequeApi = { list(q), due(q), toDeposit(q), postDated(q), summary(propertyId?), aging(propertyId?), statsByLeases(ids), get(id),
                           deposit(id, body), depositBatch(body), clear(id, body), receive(id, body), bounce(id, body), replace(id, body), cancel(id, body),
                           updateDetails(id, body), cashReceipt(leaseId, body), receiptUrl(id) }
export const penaltyApi = { list(q), propose(body), approve(id, date), waive(id, note), reverse(id, {date, note}), mine() }
export const onlinePayApi = { myPayments(), createOrder(chequeId), verify(body), cancel(chequeId) }
```
i18n `Leasing`: `contractDate, contractNumber, trackingChain, status.RENEWED, particulars, creditAccount, grossAmount, discount, netAmount, contractValue, chequeGrid, generateCheques, generateChequeNumbers, installments, firstDueDate, distribution, foldIntoFirst, payeeBank, debitAccount, mode.PDC/CASH/TRANSFER/ONLINE, postLease, postConfirm ("Post contract {number}? This writes {n} journal entries and cannot be edited without a reversal."), posted ("Posted as {tco}"), amendLines, amendReason, renew, renewFrom, carryDepositForward, extend, newEndDate, extensionLines, recognitionSchedule (placeholder tab, Plan 3), journalsTab, chequesMustEqual ("Cheques total {cheques} but contract value is {contract}"), unmappedRoles`.
i18n `Cheques`: `register, collection, returnReplace, postDated, summary.*, status.DRAFT…ONLINE_PENDING, deposit, depositBatch, depositDate, clear, receive, bounce, failureReason, replace, replacements, cancel, details, cashReceipt, due, overdue, daysOverdue, maturity, receipt, penalties, propose, approve, waive, reverse, proposedQueue, approvedList, reason.*`.

- [ ] Steps: failing vitest for URL building (`chequeApi.list({status:"REGISTERED", propertyId:"p"})` → `/api/proxy/v1/cheques?status=REGISTERED&propertyId=p`), implement, `tsc`, commit `feat(web): leasing/cheque/penalty API client, roles, i18n`.

---

### Task 14: Web — lease editor in PACT layout (wizard + detail page)

**Files:**
- Create: `components/leases/LeaseLinesGrid.tsx`, `ChequeGrid.tsx`, `PostLeaseDialog.tsx`, `AmendLinesDialog.tsx`, `RenewLeaseDialog.tsx`, `ExtendLeaseDialog.tsx`, `LeaseJournalsTab.tsx`
- Modify: `dashboard/leases/LeaseWizard.tsx`, `leases/[id]/page.tsx`, `leases/page.tsx`, `leases/LeaseMetadataEditor.tsx` (contract fields), delete `leases/PaymentScheduleEditor.tsx`
- Test: `components/leases/__tests__/LeaseLinesGrid.test.tsx`, `ChequeGrid.test.tsx`, `leases/[id]/__tests__/post-gate.test.tsx`

**Interfaces:**
- `LeaseLinesGrid({ lines, chargeTypes, propertyId, editable, onChange })` — columns SNO | Particulars (charge type select) | Credit A/c (`AccountPicker`, pre-filled, editable) | Amount | Discount | After Discount (computed, read-only) | Narration | VAT (checkbox); footer totals; add/remove rows; validates discount ≤ amount inline.
- `ChequeGrid({ cheques, editable, onChange, onGenerate, onGenerateNumbers, defaultBankAccountId })` — columns SNO | Posting Date | Cheque No | Date | Payee Bank | Debit A/c | Amount | Narration | Mode; footer total + "Cheques total vs contract value" indicator (green when equal); *Generate cheques* opens a small form (installments, first due date, distribution, fold, payee bank, debit account) → `leaseApi.generateCheques`; *Generate cheque numbers* prompts for the starting number.
- Wizard steps: Parties → Terms (contract date, start, end, grace, payment terms, first due date, VAT) → Lines (`LeaseLinesGrid`) → Cheques (`ChequeGrid`, editable, on a saved draft — the wizard saves the draft at the end of the Lines step, then generates) → Review (contract value, cheque total, unmapped-role warnings from a dry-run `POST /{id}/post?dryRun=true` — add this query flag to Task 6's endpoint: returns validation errors without posting).
- Detail page: header (status incl. RENEWED, `displayContractNumber`, chain link "Renewed from …" / "Renewed by …"), action bar **Save / Post / Amend / Renew / Extend / Ledger / Terminate** gated by status + `canPostLeases`; tabs: Overview (header fields + `LeaseLinesGrid` read-only + `ChequeGrid` read-only with row actions deposit/clear/bounce/replace/details), **Journals** (`LeaseJournalsTab` = `LedgerTable` of `ledgerApi.ledger.renter(renterId, {leaseId})` plus list of entries from `leaseApi.journals`), Recognition schedule (placeholder "available after month-end module" — Plan 3 fills it), Penalties (assessments for this lease with propose/approve/waive), Contract, Maintenance, Documents, Interactions (kept).
- `PostLeaseDialog`: shows contract value, cheque total, list of journals about to be written ("1 TCO with n lines, m PDR"), warnings; Post → `leaseApi.post` → toast `posted` → refresh.
- Leases list: status filter includes RENEWED; new column "Chain" (short id) ; **Bulk post** button for selected DRAFT rows (sequential calls, per-row result toast).

- [ ] **Step 1: Failing tests** — `LeaseLinesGrid` computes net and blocks discount > amount; `ChequeGrid` footer shows mismatch when totals differ; detail page hides **Post** for PROPERTY_MANAGER and shows it for ACCOUNTANT on a DRAFT.
- [ ] **Step 2: Implement.** Keep the existing visual language (cards, table classes) from `leases/[id]/page.tsx`; replace `Payment` type/fetches with `Cheque`/`chequeApi`; remove `PaymentScheduleEditor`, `CollectChequeDialog`, `MarkChequeFailedDialog` usages (bounce dialog is new `BounceChequeDialog` with failure reason + date).
- [ ] **Step 3: vitest + tsc + lint, commit** `feat(web): lease editor with lines and cheque grid, post/amend/renew/extend`.

---

### Task 15: Web — Cheque register pages (replace Payments)

**Files:**
- Create: `finance/cheques/page.tsx`, `cheques/collection/page.tsx`, `cheques/return-replace/page.tsx`, `cheques/post-dated/page.tsx`, `components/cheques/{ChequeStatusBadge,DepositBatchDialog,ReplaceChequeDialog,BounceChequeDialog,ReceiveCashDialog}.tsx`
- Modify: `components/dashboard/ChequesToDepositWidget.tsx`, `OverduePaymentsWidget.tsx`, `components/ui/GlobalSearch.tsx` (search → `chequeApi.list({search})`), `MvpSidebar.tsx` (Payments → Cheques group: Register, Collection, Return / Replace, Post-dated), `components/cheques/BulkChequeUploadFlow.tsx` + `autoMapChequesToSchedules.ts` (map to cheques by amount/date; endpoint unchanged path but body targets cheque ids)
- Delete: `finance/payments/page.tsx` + `__tests__`, `components/payments/*`, `e2e/finance/payments-pdc.spec.ts`
- Test: `finance/cheques/__tests__/register-actions.test.tsx`, `cheques/collection/__tests__/batch.test.tsx`

**Interfaces:**
- **Register**: summary cards from `chequeApi.summary`; filters status/mode/property/date range/search; table Cheque No | Date | Tenant | Unit | Property | Amount | Mode | Status | Due; row actions by status (REGISTERED: Deposit / Receive(cash) / Details / Cancel; DEPOSITED: Clear / Bounce; CLEARED: Bounce (late return) / Receipt; BOUNCED: Replace); server pagination.
- **Collection** (PACT "Cheque / Cash Collection"): `chequeApi.toDeposit` list with checkboxes → `DepositBatchDialog` (deposit date, bank account) → `depositBatch`.
- **Return / Replace**: BOUNCED rows → `ReplaceChequeDialog` (one or more replacement rows: number, date, bank, amount; default one row same amount) → `replace`.
- **Post-dated received**: month picker → `postDated` grouped by maturity date with running total.

- [ ] Steps: failing tests (register shows the right actions per status; batch dialog posts the selected ids), implement, delete old pages, `tsc`/lint/vitest, commit `feat(web): cheque register, collection batch, return/replace, post-dated views`.

---

### Task 16: Web — Penalties queue, lease penalties tab, renter portal (due rows, approved penalties, Razorpay)

**Files:**
- Create: `finance/penalties/page.tsx`, `components/penalties/PenaltyQueue.tsx`, `components/renter/PayOnlineButton.tsx`
- Modify: `renter-portal/payments/page.tsx` (rows from `onlinePayApi.myPayments`; "Pay" on due rows; receipt on CLEARED), `renter-portal/penalties/page.tsx` (APPROVED assessments, read-only), `settings/fines/page.tsx` (three new fields), `MvpSidebar.tsx` (Penalties under Cheques)
- Delete: `components/penalties/RecordPenaltyPaymentDialog.tsx`, `leases/[id]/__tests__/waive-role-gate.test.tsx` (rewrite as approve-role gate)
- Test: `finance/penalties/__tests__/queue.test.tsx`, `renter-portal/payments/__tests__/page.test.tsx` (rewrite)

**Interfaces:**
- `PenaltyQueue({ status })` — tabs Proposed / Approved / Waived / Reversed; row: tenant, property, cheque no, reason, amount, proposed by/at; actions Approve (date picker) / Waive (note) on PROPOSED, Reverse (date + note) on APPROVED; gated by `canApprovePenalties`.
- `PayOnlineButton({ cheque })` — loads `sdkJsUrl` from `createOrder` response, opens Razorpay checkout (`new window.Razorpay({ key, amount, currency, order_id, handler })`), on handler → `onlinePayApi.verify({gatewayOrderId, gatewayPaymentId, gatewaySignature})` → refresh; on modal dismiss → `onlinePayApi.cancel(chequeId)`. Only rendered when the property has online payments enabled (`RentCollectionSettings.onlinePaymentEnabled` exposed on `RenterChequeDTO.onlineEnabled`).
- Renter payments page: two lists — **Due now** (due rows incl. approved-penalty rows, Pay button) and **History** (CLEARED with receipt link, BOUNCED flagged); PACT-style running "Amount due" total.

- [ ] Steps: failing tests (queue actions gated by role; renter page shows Pay only on due rows), implement, vitest/tsc/lint, commit `feat(web): penalty approval queue, renter due list with online payment`.

---

### Task 17: Full verification, walkthrough smoke, PR

- [ ] `cd backend && ./gradlew test` → all green (record counts).
- [ ] `cd web && npx tsc --noEmit && npm run lint && npx vitest run` → green.
- [ ] Playwright dev: `web/e2e/leases/lease-lifecycle.spec.ts` and `leases-crud.spec.ts` rewritten for lines + cheques + Post; `e2e/finance/cheques.spec.ts` new: generate → post → deposit → clear → bounce → replace → tenant ledger nets to zero, then shows the bounce reopening 12,750 → approve penalty → receive cash. `npx playwright test e2e/leases e2e/finance`.
- [ ] `scripts/seed_demo_tenant.py`: minimal fix so it runs (create leases with `lines` + `generateCheques` + `post`, drive cheques via `/api/v1/cheques/{id}/deposit|clear|bounce`); the full rewrite is Plan 5.
- [ ] Commit, push `feat/accounting-v2-lease-posting`, open PR "feat(leasing): accounting v2 plan 2 — lease posting & PDC register" with the same body structure as Plan 1's PR (summary, changesets 83/84, what's not in this PR: recognition/termination/settlement (Plan 3), vouchers/cut-over (Plan 4), mobile), verification counts, attribution footer.

---

## Self-review

**Spec coverage:** §6.1 ChargeType (Task 2) ✔ · §6.2 LeaseLine + discount informational (Task 4) ✔ · §6.3 header fields, RENEWED, chain, overrides, contract number display (Tasks 1, 4) ✔ (derived `rent_amount`/`deposit_amount` kept — recorded as a spec amendment in Task 4 Step 5) · §6.4 Post validation + TCO pairs + PDR per row + ledger nets to zero (Task 6) ✔ · §6.5 amend by reversal; cheque edits only while REGISTERED (Tasks 6, 11 `details`) ✔ · §6.6 Renew + carry deposit JV + RENEWED (Task 8) ✔ · §6.7 Extend additive + event for Plan 3 (Task 8) ✔ · §6.8 generator with tens rounding, first absorbs, fold, numbers (Task 5) ✔ · §7.1 Cheque fields (Tasks 1, 3) ✔ · §7.2 every transition incl. CLEARED→BOUNCED to bank, REPLACED residual, CANCELLED, RETURNED (Task 7; RETURNED exposed for Plan 3), ONLINE (Task 10) ✔ · §7.3 PenaltyAssessment, rule threshold, approve→PEN + collection row, waive/reverse, renter sees APPROVED, nightly job no longer posts (Task 9; `PenaltyService` deleted in Task 12) ✔ · §7.4 register screens + bulk OCR attach (Tasks 11, 15) ✔ · §7.5 due/overdue predicates on the register (Tasks 3, 7, 11) ✔ · §9.3 online path + Cash Receipt Voucher (Tasks 10, 11 `cash-receipt`) ✔ · §11 Leasing / Cheque register / Penalties / Renter portal screens (Tasks 14–16) ✔ · §4.7 `payment_schedules`, `lease_charges` removed (Task 12) ✔.
**Handed to Plan 3:** `LeasePostedEvent`, `LeaseAmendedEvent`, `LeaseExtendedEvent`, `ChequeService.returnToTenant`, `LeaseService.terminateLease` still cancels uncleared cheques (Plan 3 replaces with RETURNED + unearned-rent TCR + settlement), `SettlementService` preview temporarily on register `due` rows.
**Placeholders:** none. Test bodies given as comment-outlines in Tasks 4/6 are explicit about inputs and expected messages; the implementer writes the `assertThatThrownBy` lines.
**Type consistency:** `ChequeRowInput`, `ChequeDTO`, `ChequeActionRequest`, `ReplaceChequeRequest`, `DepositBatchRequest`, `LeaseLineInput`, `PostLeaseResponse`, `PenaltyAssessmentDTO` are defined once (Tasks 3–9) and referenced by the same names in Tasks 10–16 and in `leasing.ts`. `ofPairs`/`Pair` come from Plan 1 Addendum A.
