# Accounting v2 — Plan 1: Ledger Core — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace v1's one-legged `financial_transactions` with an immutable, balanced, role-resolved journal (accounts tree with property dimension, `PostingService`, `AccountResolver`, per-property account sets, fiscal period lock, manual JV) and the four control views (General Ledger, Tenant Ledger, Vendor Ledger, Trial Balance) on the web.

**Architecture:** Every document produces one `JournalEntry` with ≥2 `JournalLine`s through a single `PostingService.post()`; the DB enforces balance with a deferred constraint trigger and immutability with row triggers. Callers name `AccountRole`s; `AccountResolver` maps `(role, propertyId)` → account via `property_account_mappings` then `tenant_default_account_mappings`. Properties get their account set from a tenant template on creation. v1 finance tables, services, pages are deleted at the end of this plan (the cheque/lease services keep their status logic but stop posting until Plan 2 rewires them).

**Tech Stack:** Java 21, Spring Boot 4.0.3, Spring Data JPA (Hibernate 7, `BaseTenantEntity` + `tenantFilter`), Liquibase YAML, PostgreSQL 16, JUnit 5 + Testcontainers (`postgres:16-alpine`), AssertJ, Mockito; Next.js 16 + TypeScript + Tailwind 4 + next-intl, Vitest + Testing Library, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-17-accounting-v2-design.md` — sections §4 (ledger core), §5 (property account sets), §11 (finance screens: CoA, GL, Tenant/Vendor Ledger, TB, JV), §4.7 (removals). Read it first.

## Global Constraints

- Liquibase changesets are **append-only**; next numbers are `81-` and `82-`; `changeSet.id` = filename stem; `author: claude`; a `#` comment block explains why.
- Every tenant-scoped entity extends `BaseTenantEntity` (Hibernate `tenantFilter`, `tenant_id` filled in `@PrePersist` from `TenantContextHolder`). Repositories live in `domain/repository` so `TenantAspect` enables the filter.
- Amounts are `BigDecimal` `precision = 14, scale = 2`; DB `decimal(14,2)`. Currency AED, no multi-currency.
- Exceptions: `NotFoundException` → 404, `BusinessRuleViolationException` → 400 (`api/exception`). Response bodies are `{error, message, status}`.
- Endpoint auth is Spring `@PreAuthorize("hasAnyRole(...)")`. Role authority is `ROLE_<UserRole>`; principal is the user id `String`.
- Journal entries and lines are **immutable** after insert: no update/delete endpoints, ever. Only `reverse()`.
- Roles, not account codes, in code. The only place an account code string may appear in Java is the CoA seed and the template seed.
- Web calls go through `fetch("/api/proxy/v1/...")`; never hardcode the backend URL. UI is table-first with `Pagination`, AR/EN via `useTranslations("Finance")`, RTL-safe.
- Backend tests: `…Test` = unit (Mockito), `…IT` = Testcontainers `@SpringBootTest`. Run a single class with `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.<pkg>.<Class>'`.
- Commit after every task with a conventional-commit message; work on branch `feat/accounting-v2-ledger-core` cut from `docs/accounting-v2-design` (plain branch, no worktree).
- Wipe-and-reseed is agreed (spec D4): no data migration for `financial_transactions`; `accounts` rows are kept and their `parent_code` is converted to `parent_id`.

---

## File structure

**Backend — new**
- `domain/entity/enums/AccountRole.java`, `JournalDocType.java`, `JournalStatus.java`, `JournalSourceType.java`
- `domain/entity/JournalEntry.java`, `JournalLine.java`, `JournalEntrySequence.java`, `TenantFiscalSettings.java`, `PropertyAccountMapping.java`, `TenantDefaultAccountMapping.java`, `PropertyAccountTemplateRow.java`
- `domain/repository/JournalEntryRepository.java`, `JournalLineRepository.java`, `JournalEntrySequenceRepository.java`, `TenantFiscalSettingsRepository.java`, `PropertyAccountMappingRepository.java`, `TenantDefaultAccountMappingRepository.java`, `PropertyAccountTemplateRowRepository.java`
- `core/service/ledger/EntryNumberService.java` — `TCO-26/1629` numbering
- `core/service/ledger/TenantFiscalSettingsService.java` — fiscal year + period lock
- `core/service/ledger/AccountResolver.java` — role → account
- `core/service/ledger/PostingService.java` + `PostingRequest.java` (records) + `UnmappedAccountRoleException.java`
- `core/service/ledger/PropertyAccountService.java` — template, generate-missing, mappings
- `core/service/ledger/LedgerQueryService.java` — GL, tenant/vendor ledger, trial balance
- `core/service/ledger/JournalService.java` — manual JV, list/detail/reverse
- `api/LedgerController.java`, `api/JournalController.java`, `api/PropertyAccountController.java`, `api/FiscalSettingsController.java`
- `api/dto/ledger/*.java` — request/response records
- `db/changelog/changesets/81-ledger-core.yaml`, `82-drop-v1-finance.yaml`

**Backend — modified**
- `domain/entity/Account.java` (parent_id, property_id, alias; drop parentCode/hierarchyLevel), `AccountRepository`, `AccountService`, `AccountController`, `AccountImportService`
- `domain/entity/enums/UserRole.java` (+`ACCOUNTANT`)
- `core/service/PropertyService.java` (hook), `VendorService.java` (silent account), `BankAccountService.java` (default coa)
- `PaymentScheduleService`, `OnlinePaymentService`, `SettlementService`, `PenaltyPaymentService`, `AccountDeletionService` — strip `FinancialTransactionService`/`AccountMappingService` usages

**Backend — deleted (Task 15)**
- `FinancialTransaction`, `FinancialTransactionRepository`, `FinancialTransactionService`, `FinancialTransactionController`, `AccountMapping`, `AccountMappingRepository`, `AccountMappingService`, `AccountMappingController`, `AccountMappingDTO`, `SaveAccountMappingDTO`, `TransactionNature`, report DTOs that only `FinancialTransactionController` used, and their tests.

**Web — new**
- `web/src/lib/api/ledger.ts` — typed fetch helpers + types for every endpoint in this plan
- `web/src/app/[locale]/dashboard/finance/general-ledger/page.tsx`
- `web/src/app/[locale]/dashboard/finance/tenant-ledger/page.tsx`
- `web/src/app/[locale]/dashboard/finance/trial-balance/page.tsx`
- `web/src/app/[locale]/dashboard/finance/journals/page.tsx`, `journals/[id]/page.tsx`, `journals/new/page.tsx`
- `web/src/app/[locale]/dashboard/settings/account-template/page.tsx`, `settings/fiscal/page.tsx`
- `web/src/components/finance/PropertyAccountsTab.tsx` (mounted on the property detail page)

**Web — modified**
- `finance/accounts/page.tsx` (parentId, property filter, alias), `finance/vendors/page.tsx` (Ledger link), `components/ui/MvpSidebar.tsx`, `lib/rbac.ts`, `messages/en.json`, `messages/ar.json`, property detail page (Accounts tab)

**Web — deleted (Task 15)**
- `finance/transactions/page.tsx`, `finance/reports/page.tsx`, `settings/account-mappings/page.tsx`, `e2e/finance/transactions.spec.ts`, `e2e/finance/reports.spec.ts`

---

### Task 1: Changeset 81 — ledger core schema

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/81-ledger-core.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (append include)
- Test: `backend/src/test/java/com/datagami/rentaxis/domain/LedgerSchemaIT.java`

**Interfaces:**
- Produces: tables `journal_entries`, `journal_lines`, `journal_entry_sequences`, `tenant_fiscal_settings`, `property_account_mappings`, `tenant_default_account_mappings`, `property_account_template_rows`; `accounts.parent_id`, `accounts.property_id`, `accounts.alias`; triggers `trg_journal_lines_balanced`, `trg_journal_lines_immutable`, `trg_journal_entries_immutable`.

- [ ] **Step 1: Write the failing schema test**

```java
package com.datagami.rentaxis.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class LedgerSchemaIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO landlord_orgs (id, name) VALUES (?, ?)", id, "T-" + id);
        return id;
    }

    private UUID account(UUID tenant, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, tenant_id, code, name, account_type, is_group, is_active, is_system, display_order) VALUES (?,?,?,?,?,false,true,false,0)",
                id, tenant, code, "Acct " + code, "ASSET");
        return id;
    }

    private UUID entry(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO journal_entries (id, tenant_id, entry_number, doc_type, entry_date, status, created_at) VALUES (?,?,?,?,CURRENT_DATE,'POSTED',now())",
                id, tenant, "JV-26/" + id.toString().substring(0, 6), "JV");
        return id;
    }

    @Test
    void unbalancedEntryIsRejectedAtCommit() {
        UUID t = tenant(); UUID a = account(t, "1"); UUID b = account(t, "2"); UUID e = entry(t);
        assertThatThrownBy(() -> jdbc.execute(
                "BEGIN; " +
                "INSERT INTO journal_lines (id, tenant_id, journal_entry_id, line_no, account_id, debit, credit) VALUES ('" + UUID.randomUUID() + "','" + t + "','" + e + "',1,'" + a + "',100,0);" +
                "INSERT INTO journal_lines (id, tenant_id, journal_entry_id, line_no, account_id, debit, credit) VALUES ('" + UUID.randomUUID() + "','" + t + "','" + e + "',2,'" + b + "',0,90);" +
                "COMMIT;"))
                .hasMessageContaining("journal entry is not balanced");
    }

    @Test
    void balancedEntryCommits() {
        UUID t = tenant(); UUID a = account(t, "3"); UUID b = account(t, "4"); UUID e = entry(t);
        jdbc.execute("BEGIN; " +
                "INSERT INTO journal_lines (id, tenant_id, journal_entry_id, line_no, account_id, debit, credit) VALUES ('" + UUID.randomUUID() + "','" + t + "','" + e + "',1,'" + a + "',100,0);" +
                "INSERT INTO journal_lines (id, tenant_id, journal_entry_id, line_no, account_id, debit, credit) VALUES ('" + UUID.randomUUID() + "','" + t + "','" + e + "',2,'" + b + "',0,100);" +
                "COMMIT;");
        Integer n = jdbc.queryForObject("SELECT count(*) FROM journal_lines WHERE journal_entry_id = ?", Integer.class, e);
        assertThat(n).isEqualTo(2);
    }

    @Test
    void lineWithBothSidesIsRejected() {
        UUID t = tenant(); UUID a = account(t, "5"); UUID e = entry(t);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO journal_lines (id, tenant_id, journal_entry_id, line_no, account_id, debit, credit) VALUES (?,?,?,1,?,10,10)",
                UUID.randomUUID(), t, e, a))
                .hasMessageContaining("ck_journal_lines_one_side");
    }

    @Test
    void postedLinesCannotBeUpdatedOrDeleted() {
        UUID t = tenant(); UUID a = account(t, "6"); UUID b = account(t, "7"); UUID e = entry(t);
        UUID l1 = UUID.randomUUID();
        jdbc.execute("BEGIN; " +
                "INSERT INTO journal_lines (id, tenant_id, journal_entry_id, line_no, account_id, debit, credit) VALUES ('" + l1 + "','" + t + "','" + e + "',1,'" + a + "',50,0);" +
                "INSERT INTO journal_lines (id, tenant_id, journal_entry_id, line_no, account_id, debit, credit) VALUES ('" + UUID.randomUUID() + "','" + t + "','" + e + "',2,'" + b + "',0,50);" +
                "COMMIT;");
        assertThatThrownBy(() -> jdbc.update("UPDATE journal_lines SET debit = 60 WHERE id = ?", l1))
                .hasMessageContaining("journal lines are immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM journal_lines WHERE id = ?", l1))
                .hasMessageContaining("journal lines are immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE journal_entries SET entry_date = entry_date - 1 WHERE id = ?", e))
                .hasMessageContaining("journal entries are immutable");
        // status / reversed_by_id are the only mutable columns
        jdbc.update("UPDATE journal_entries SET status = 'REVERSED', reversed_by_id = ? WHERE id = ?", e, e);
    }

    @Test
    void accountsHaveParentIdAndPropertyId() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'accounts' AND column_name IN ('parent_id','property_id','alias')",
                Integer.class);
        assertThat(n).isEqualTo(3);
        Integer old = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'accounts' AND column_name IN ('parent_code','hierarchy_level')",
                Integer.class);
        assertThat(old).isZero();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.domain.LedgerSchemaIT'`
Expected: FAIL — `relation "journal_entries" does not exist`.

- [ ] **Step 3: Write the changeset**

`backend/src/main/resources/db/changelog/changesets/81-ledger-core.yaml`:

```yaml
databaseChangeLog:
  # Accounting v2, plan 1 (docs/superpowers/specs/2026-09-17-accounting-v2-design.md §4-§5).
  #
  # Introduces the immutable double-entry journal that replaces the one-legged
  # financial_transactions table (dropped in 82), the per-property account
  # mappings resolved by role, the tenant account template, fiscal settings
  # with a period lock, and per-tenant/doc-type/fiscal-year entry numbering.
  #
  # accounts: parent_code (string) becomes parent_id (FK) so the tree is
  # enforced; property_id tags leaves that belong to one building (both the
  # template-generated ones and expense leaves the accountant assigns).
  # hierarchy_level is dropped — depth is derived from the tree.
  #
  # Balance is enforced by a DEFERRABLE INITIALLY DEFERRED constraint trigger
  # so an entry's lines can be inserted one by one inside a transaction and are
  # checked once at commit. Immutability triggers make "reverse, never edit"
  # a database guarantee, not a convention.
  - changeSet:
      id: 81-ledger-core
      author: claude
      changes:
        # ---- accounts: tree by id, property dimension ----
        - addColumn:
            tableName: accounts
            columns:
              - column: { name: parent_id, type: uuid }
              - column: { name: property_id, type: uuid }
              - column: { name: alias, type: varchar(255) }
        - sql:
            comment: Backfill parent_id from the legacy parent_code within the same tenant
            sql: >-
              UPDATE accounts c SET parent_id = p.id
              FROM accounts p
              WHERE c.parent_code IS NOT NULL
                AND p.code = c.parent_code
                AND p.tenant_id = c.tenant_id
        - addForeignKeyConstraint:
            baseTableName: accounts
            baseColumnNames: parent_id
            referencedTableName: accounts
            referencedColumnNames: id
            constraintName: fk_accounts_parent
        - addForeignKeyConstraint:
            baseTableName: accounts
            baseColumnNames: property_id
            referencedTableName: properties
            referencedColumnNames: id
            constraintName: fk_accounts_property
            onDelete: SET NULL
        - createIndex: { tableName: accounts, indexName: idx_accounts_parent, columns: [ { column: { name: parent_id } } ] }
        - createIndex: { tableName: accounts, indexName: idx_accounts_property, columns: [ { column: { name: property_id } } ] }
        - dropColumn: { tableName: accounts, columnName: parent_code }
        - dropColumn: { tableName: accounts, columnName: hierarchy_level }

        # ---- fiscal settings ----
        - createTable:
            tableName: tenant_fiscal_settings
            columns:
              - column: { name: tenant_id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: fiscal_year_start_month, type: int, constraints: { nullable: false }, defaultValueNumeric: 1 }
              - column: { name: books_start_date, type: date }
              - column: { name: books_locked_through, type: date }
              - column: { name: next_account_code, type: bigint }
              - column: { name: updated_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }

        # ---- entry numbering ----
        - createTable:
            tableName: journal_entry_sequences
            columns:
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: doc_type, type: varchar(10), constraints: { nullable: false } }
              - column: { name: fiscal_year, type: int, constraints: { nullable: false } }
              - column: { name: next_value, type: bigint, constraints: { nullable: false }, defaultValueNumeric: 1 }
        - addPrimaryKey:
            tableName: journal_entry_sequences
            columnNames: tenant_id, doc_type, fiscal_year
            constraintName: pk_journal_entry_sequences

        # ---- journal ----
        - createTable:
            tableName: journal_entries
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: entry_number, type: varchar(40), constraints: { nullable: false } }
              - column: { name: doc_type, type: varchar(10), constraints: { nullable: false } }
              - column: { name: entry_date, type: date, constraints: { nullable: false } }
              - column: { name: narration, type: text }
              - column: { name: property_id, type: uuid, constraints: { foreignKeyName: fk_je_property, referencedTableName: properties, referencedColumnNames: id } }
              - column: { name: unit_id, type: uuid, constraints: { foreignKeyName: fk_je_unit, referencedTableName: units, referencedColumnNames: id } }
              - column: { name: lease_id, type: uuid, constraints: { foreignKeyName: fk_je_lease, referencedTableName: leases, referencedColumnNames: id } }
              - column: { name: renter_id, type: uuid, constraints: { foreignKeyName: fk_je_renter, referencedTableName: renters, referencedColumnNames: id } }
              - column: { name: source_type, type: varchar(30) }
              - column: { name: source_id, type: uuid }
              - column: { name: status, type: varchar(10), constraints: { nullable: false }, defaultValue: POSTED }
              - column: { name: reversal_of_id, type: uuid, constraints: { foreignKeyName: fk_je_reversal_of, referencedTableName: journal_entries, referencedColumnNames: id } }
              - column: { name: reversed_by_id, type: uuid, constraints: { foreignKeyName: fk_je_reversed_by, referencedTableName: journal_entries, referencedColumnNames: id } }
              - column: { name: import_batch_id, type: uuid }
              - column: { name: posted_by, type: uuid }
              - column: { name: posted_at, type: timestamptz }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
        - addUniqueConstraint: { tableName: journal_entries, columnNames: tenant_id, entry_number, constraintName: uq_journal_entries_number }
        - createIndex: { tableName: journal_entries, indexName: idx_je_tenant_date, columns: [ { column: { name: tenant_id } }, { column: { name: entry_date } } ] }
        - createIndex: { tableName: journal_entries, indexName: idx_je_property, columns: [ { column: { name: property_id } } ] }
        - createIndex: { tableName: journal_entries, indexName: idx_je_lease, columns: [ { column: { name: lease_id } } ] }
        - createIndex: { tableName: journal_entries, indexName: idx_je_renter, columns: [ { column: { name: renter_id } } ] }
        - createIndex: { tableName: journal_entries, indexName: idx_je_source, columns: [ { column: { name: source_type } }, { column: { name: source_id } } ] }
        - createIndex: { tableName: journal_entries, indexName: idx_je_import_batch, columns: [ { column: { name: import_batch_id } } ] }

        - createTable:
            tableName: journal_lines
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: journal_entry_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_jl_entry, referencedTableName: journal_entries, referencedColumnNames: id } }
              - column: { name: line_no, type: int, constraints: { nullable: false } }
              - column: { name: account_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_jl_account, referencedTableName: accounts, referencedColumnNames: id } }
              - column: { name: debit, type: "decimal(14,2)", constraints: { nullable: false }, defaultValueNumeric: 0 }
              - column: { name: credit, type: "decimal(14,2)", constraints: { nullable: false }, defaultValueNumeric: 0 }
              - column: { name: property_id, type: uuid }
              - column: { name: unit_id, type: uuid }
              - column: { name: lease_id, type: uuid }
              - column: { name: renter_id, type: uuid }
              - column: { name: cheque_id, type: uuid }
              - column: { name: narration, type: text }
        - sql:
            comment: Exactly one side per line, strictly positive
            sql: >-
              ALTER TABLE journal_lines ADD CONSTRAINT ck_journal_lines_one_side
              CHECK ((debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0))
        - createIndex: { tableName: journal_lines, indexName: idx_jl_entry, columns: [ { column: { name: journal_entry_id } } ] }
        - createIndex: { tableName: journal_lines, indexName: idx_jl_tenant_account, columns: [ { column: { name: tenant_id } }, { column: { name: account_id } } ] }
        - createIndex: { tableName: journal_lines, indexName: idx_jl_renter, columns: [ { column: { name: renter_id } } ] }
        - createIndex: { tableName: journal_lines, indexName: idx_jl_lease, columns: [ { column: { name: lease_id } } ] }
        - createIndex: { tableName: journal_lines, indexName: idx_jl_property, columns: [ { column: { name: property_id } } ] }

        - sql:
            comment: Balance check, deferred to commit so lines can be inserted one at a time
            splitStatements: false
            sql: |
              CREATE OR REPLACE FUNCTION journal_lines_check_balanced() RETURNS trigger AS $$
              DECLARE
                v_entry uuid;
                v_debit numeric;
                v_credit numeric;
                v_count int;
              BEGIN
                v_entry := COALESCE(NEW.journal_entry_id, OLD.journal_entry_id);
                SELECT COALESCE(SUM(debit),0), COALESCE(SUM(credit),0), COUNT(*)
                  INTO v_debit, v_credit, v_count
                  FROM journal_lines WHERE journal_entry_id = v_entry;
                IF v_count < 2 OR v_debit <> v_credit THEN
                  RAISE EXCEPTION 'journal entry % is not balanced (debit %, credit %, lines %)',
                    v_entry, v_debit, v_credit, v_count;
                END IF;
                RETURN NULL;
              END;
              $$ LANGUAGE plpgsql;

              CREATE CONSTRAINT TRIGGER trg_journal_lines_balanced
              AFTER INSERT OR UPDATE OR DELETE ON journal_lines
              DEFERRABLE INITIALLY DEFERRED
              FOR EACH ROW EXECUTE FUNCTION journal_lines_check_balanced();

              CREATE OR REPLACE FUNCTION journal_lines_immutable() RETURNS trigger AS $$
              BEGIN
                RAISE EXCEPTION 'journal lines are immutable; reverse the entry instead';
              END;
              $$ LANGUAGE plpgsql;

              CREATE TRIGGER trg_journal_lines_immutable
              BEFORE UPDATE OR DELETE ON journal_lines
              FOR EACH ROW EXECUTE FUNCTION journal_lines_immutable();

              CREATE OR REPLACE FUNCTION journal_entries_immutable() RETURNS trigger AS $$
              BEGIN
                IF TG_OP = 'DELETE' THEN
                  RAISE EXCEPTION 'journal entries are immutable; reverse the entry instead';
                END IF;
                IF NEW.entry_number IS DISTINCT FROM OLD.entry_number
                   OR NEW.doc_type IS DISTINCT FROM OLD.doc_type
                   OR NEW.entry_date IS DISTINCT FROM OLD.entry_date
                   OR NEW.narration IS DISTINCT FROM OLD.narration
                   OR NEW.property_id IS DISTINCT FROM OLD.property_id
                   OR NEW.unit_id IS DISTINCT FROM OLD.unit_id
                   OR NEW.lease_id IS DISTINCT FROM OLD.lease_id
                   OR NEW.renter_id IS DISTINCT FROM OLD.renter_id
                   OR NEW.source_type IS DISTINCT FROM OLD.source_type
                   OR NEW.source_id IS DISTINCT FROM OLD.source_id
                   OR NEW.reversal_of_id IS DISTINCT FROM OLD.reversal_of_id
                   OR NEW.import_batch_id IS DISTINCT FROM OLD.import_batch_id
                   OR NEW.posted_by IS DISTINCT FROM OLD.posted_by
                   OR NEW.posted_at IS DISTINCT FROM OLD.posted_at
                   OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
                  RAISE EXCEPTION 'journal entries are immutable; only status and reversed_by_id may change';
                END IF;
                RETURN NEW;
              END;
              $$ LANGUAGE plpgsql;

              CREATE TRIGGER trg_journal_entries_immutable
              BEFORE UPDATE OR DELETE ON journal_entries
              FOR EACH ROW EXECUTE FUNCTION journal_entries_immutable();

        # ---- role mappings + template ----
        - createTable:
            tableName: property_account_mappings
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: property_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_pam_property, referencedTableName: properties, referencedColumnNames: id, deleteCascade: true } }
              - column: { name: role, type: varchar(40), constraints: { nullable: false } }
              - column: { name: account_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_pam_account, referencedTableName: accounts, referencedColumnNames: id } }
        - addUniqueConstraint: { tableName: property_account_mappings, columnNames: property_id, role, constraintName: uq_pam_property_role }
        - createIndex: { tableName: property_account_mappings, indexName: idx_pam_tenant, columns: [ { column: { name: tenant_id } } ] }

        - createTable:
            tableName: tenant_default_account_mappings
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: role, type: varchar(40), constraints: { nullable: false } }
              - column: { name: account_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_tdam_account, referencedTableName: accounts, referencedColumnNames: id } }
        - addUniqueConstraint: { tableName: tenant_default_account_mappings, columnNames: tenant_id, role, constraintName: uq_tdam_tenant_role }

        - createTable:
            tableName: property_account_template_rows
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: role, type: varchar(40), constraints: { nullable: false } }
              - column: { name: name_pattern, type: varchar(255), constraints: { nullable: false } }
              - column: { name: parent_account_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_patr_parent, referencedTableName: accounts, referencedColumnNames: id } }
              - column: { name: enabled, type: boolean, constraints: { nullable: false }, defaultValueBoolean: true }
        - addUniqueConstraint: { tableName: property_account_template_rows, columnNames: tenant_id, role, constraintName: uq_patr_tenant_role }
      rollback:
        - sql:
            sql: |
              DROP TABLE IF EXISTS property_account_template_rows, tenant_default_account_mappings, property_account_mappings, journal_lines, journal_entries, journal_entry_sequences, tenant_fiscal_settings;
              DROP FUNCTION IF EXISTS journal_lines_check_balanced(), journal_lines_immutable(), journal_entries_immutable();
```

Append to `db.changelog-master.yaml` after the `80-…` include:

```yaml
  - include:
      file: db/changelog/changesets/81-ledger-core.yaml
```

Check the master file's existing include style (`file:` key, indentation) and match it exactly.

- [ ] **Step 4: Run the schema test**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.domain.LedgerSchemaIT'`
Expected: 5 tests PASS. If `unbalancedEntryIsRejectedAtCommit` fails with the message not containing the text, check that `splitStatements: false` is on the trigger `sql` block (otherwise Liquibase splits the function body on `;`).

- [ ] **Step 5: Run the whole backend suite to confirm nothing else broke on the `accounts` column drop**

Run: `cd backend && ./gradlew test`
Expected: compile failures in `Account`-related code are NOT expected yet (the entity still declares `parentCode`/`hierarchyLevel`, which Hibernate will now report as missing columns at boot for `@SpringBootTest` classes). If the suite fails only with `Schema-validation: missing column [parent_code]`, proceed to Task 2 immediately — Tasks 1 and 2 are committed together.

- [ ] **Step 6: Commit (after Task 2 passes)** — see Task 2 Step 8.

---

### Task 2: Account entity rework (tree by id, property, alias, code sequence, import)

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/Account.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/repository/AccountRepository.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/AccountService.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/AccountImportService.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/AccountController.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/UserRole.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/TenantFiscalSettings.java`, `.../domain/repository/TenantFiscalSettingsRepository.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/AccountServiceTreeIT.java`; update `core/service/AccountServiceTest.java`

**Interfaces:**
- Produces: `Account.getParent()`, `Account.getProperty()`, `Account.getAlias()`; `AccountRepository.findByParentIsNull()`, `findByParent_Id(UUID)`, `existsByParent_Id(UUID)`, `findByNameAndParent_Id(String, UUID)`, `findMaxNumericCode()`; `AccountService.nextLeafCode()`, `createLeaf(String name, Account parent, UUID propertyId)`, `getTree()`; `UserRole.ACCOUNTANT`.
- Consumes: Task 1 schema.

- [ ] **Step 1: Replace the tree fields on `Account`**

In `Account.java` remove the `parentCode` and `hierarchyLevel` fields and add:

```java
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Account parent;

    /** Serialized as parentId for the web; never bound from a request body (use parentId on the DTO path in AccountService). */
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public UUID getParentId() { return parent == null ? null : parent.getId(); }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Property property;

    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public UUID getPropertyId() { return property == null ? null : property.getId(); }

    @Column(length = 255)
    private String alias;
```

Add `import com.datagami.rentaxis.domain.entity.Property;` — it is in the same package, so no import is needed.

- [ ] **Step 2: Add the fiscal settings entity + repository** (needed here for the account-code sequence)

`domain/entity/TenantFiscalSettings.java`:

```java
package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One row per tenant. Not a BaseTenantEntity: tenant_id IS the primary key and the row is looked up explicitly. */
@Entity
@Table(name = "tenant_fiscal_settings")
@Getter
@Setter
public class TenantFiscalSettings {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "fiscal_year_start_month", nullable = false)
    private int fiscalYearStartMonth = 1;

    @Column(name = "books_start_date")
    private LocalDate booksStartDate;

    @Column(name = "books_locked_through")
    private LocalDate booksLockedThrough;

    /** Next numeric account code to hand out; initialised lazily from max(numeric code)+1. */
    @Column(name = "next_account_code")
    private Long nextAccountCode;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }
}
```

`domain/repository/TenantFiscalSettingsRepository.java`:

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantFiscalSettingsRepository extends JpaRepository<TenantFiscalSettings, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TenantFiscalSettings s where s.tenantId = :tenantId")
    Optional<TenantFiscalSettings> findForUpdate(UUID tenantId);
}
```

- [ ] **Step 3: Update `AccountRepository`**

Replace `findByParentCode` / `existsByParentCode` with:

```java
    List<Account> findByParentIsNullOrderByDisplayOrderAscCodeAsc();
    List<Account> findByParent_IdOrderByDisplayOrderAscCodeAsc(UUID parentId);
    boolean existsByParent_Id(UUID parentId);
    Optional<Account> findByNameAndParent_Id(String name, UUID parentId);
    List<Account> findByProperty_Id(UUID propertyId);

    /** Highest numeric code in this tenant (codes like "A-02-01" are ignored). Tenant filter applies. */
    @Query(value = "select max(code::bigint) from accounts where tenant_id = :tenantId and code ~ '^[0-9]+$'", nativeQuery = true)
    Long findMaxNumericCode(UUID tenantId);
```

Keep `findByCode`, `findByCodeAndTenantId`, `findByAccountType`.

- [ ] **Step 4: Write the failing service test**

`core/service/AccountServiceTreeIT.java`:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class AccountServiceTreeIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired AccountService service;
    @Autowired LandlordOrgRepository orgRepo;

    UUID tenantId;

    @BeforeEach
    void tenant() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Acct-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    @Test
    void seedBuildsTreeByParentId() {
        service.seedDefaultAccounts();
        Account rentalReceivable = service.getAccountByCode("A-02-01");
        assertThat(rentalReceivable.getParent()).isNotNull();
        assertThat(rentalReceivable.getParent().getCode()).isEqualTo("A-02");
        assertThat(service.getTree()).extracting(Account::getCode).contains("A-01", "A-02", "B-01", "C-01", "D-01", "F-01");
    }

    @Test
    void nextLeafCodeStartsAt100001ThenIncrements() {
        service.seedDefaultAccounts();
        assertThat(service.nextLeafCode()).isEqualTo("100001");
        assertThat(service.nextLeafCode()).isEqualTo("100002");
    }

    @Test
    void nextLeafCodeContinuesAboveImportedNumericCodes() {
        service.seedDefaultAccounts();
        Account parent = service.getAccountByCode("A-02-01");
        Account imported = new Account();
        imported.setCode("166269");
        imported.setName("Rent Receivable - L'Olivier");
        imported.setAccountType(AccountType.ASSET);
        imported.setParent(parent);
        service.createAccount(imported);
        assertThat(service.nextLeafCode()).isEqualTo("166270");
    }

    @Test
    void createLeafUnderParentInheritsTypeAndGetsSequentialCode() {
        service.seedDefaultAccounts();
        Account parent = service.getAccountByCode("A-02-01");
        Account leaf = service.createLeaf("Rent Receivable - Tulip 7", parent, null);
        assertThat(leaf.getCode()).isEqualTo("100001");
        assertThat(leaf.getAccountType()).isEqualTo(AccountType.ASSET);
        assertThat(leaf.getParent().getId()).isEqualTo(parent.getId());
        assertThat(leaf.isGroup()).isFalse();
    }

    @Test
    void cannotDeleteAccountWithChildren() {
        service.seedDefaultAccounts();
        Account a02 = service.getAccountByCode("A-02");
        // seeded accounts are system; create a non-system group with a child to test the rule
        Account group = new Account();
        group.setCode("Z-01"); group.setName("Custom group"); group.setAccountType(AccountType.EXPENSE); group.setGroup(true);
        group = service.createAccount(group);
        service.createLeaf("Custom leaf", group, null);
        UUID groupId = group.getId();
        assertThatThrownBy(() -> service.deleteAccount(groupId)).hasMessageContaining("child accounts");
        assertThat(a02.isGroup()).isTrue();
    }
}
```

- [ ] **Step 5: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.AccountServiceTreeIT'`
Expected: compile errors (`getTree`, `nextLeafCode`, `createLeaf` undefined).

- [ ] **Step 6: Rework `AccountService`**

Replace the class body (keep the package and existing imports; add the new ones):

```java
@Service
public class AccountService {

    private static final long FIRST_LEAF_CODE = 100001L;

    private final AccountRepository repository;
    private final TenantFiscalSettingsRepository fiscalRepo;

    public AccountService(AccountRepository repository, TenantFiscalSettingsRepository fiscalRepo) {
        this.repository = repository;
        this.fiscalRepo = fiscalRepo;
    }

    @Transactional(readOnly = true)
    public List<Account> getAllAccounts() { return repository.findAll(); }

    /** Root accounts (no parent). Children are fetched per node by the web via parentId. */
    @Transactional(readOnly = true)
    public List<Account> getTree() { return repository.findByParentIsNullOrderByDisplayOrderAscCodeAsc(); }

    @Transactional(readOnly = true)
    public List<Account> getChildren(UUID parentId) { return repository.findByParent_IdOrderByDisplayOrderAscCodeAsc(parentId); }

    @Transactional(readOnly = true)
    public List<Account> getAccountsByType(AccountType type) { return repository.findByAccountType(type); }

    @Transactional(readOnly = true)
    public Account getAccountByCode(String code) {
        return repository.findByCode(code)
                .orElseThrow(() -> new NotFoundException("Account not found with code: " + code));
    }

    @Transactional(readOnly = true)
    public Account getAccountById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Account not found"));
    }

    @Transactional
    public Account createAccount(Account account) {
        if (account.getParent() != null) {
            Account parent = getAccountById(account.getParent().getId());
            if (!parent.isGroup()) {
                throw new BusinessRuleViolationException("Parent account must be a group account");
            }
            account.setParent(parent);
            if (account.getAccountType() == null) {
                account.setAccountType(parent.getAccountType());
            }
        }
        if (account.getCode() == null || account.getCode().isBlank()) {
            account.setCode(nextLeafCode());
        }
        if (account.getName() == null && account.getNameEn() != null) {
            account.setName(account.getNameEn());
        }
        return repository.save(account);
    }

    /** Creates a non-group, non-system leaf under {@code parent} with the next numeric code. Used by the property template and vendor/bank silent accounts. */
    @Transactional
    public Account createLeaf(String name, Account parent, UUID propertyId) {
        Account a = new Account();
        a.setCode(nextLeafCode());
        a.setName(name);
        a.setNameEn(name);
        a.setAccountType(parent.getAccountType());
        a.setAccountSubType(parent.getAccountSubType());
        a.setParent(parent);
        a.setGroup(false);
        a.setSystem(false);
        if (propertyId != null) {
            Property p = new Property();
            p.setId(propertyId);
            a.setProperty(p);
        }
        return repository.save(a);
    }

    /**
     * Hands out the next numeric account code for the tenant. Locks the tenant's
     * fiscal-settings row so two concurrent property creations cannot collide.
     * Initialised from the highest numeric code already present (imported PACT
     * codes are 5-6 digits) or 100001 when there are none.
     */
    @Transactional
    public String nextLeafCode() {
        UUID tenantId = TenantContextHolder.getTenantId();
        TenantFiscalSettings s = fiscalRepo.findForUpdate(tenantId).orElseGet(() -> {
            TenantFiscalSettings n = new TenantFiscalSettings();
            n.setTenantId(tenantId);
            fiscalRepo.saveAndFlush(n);
            return fiscalRepo.findForUpdate(tenantId).orElseThrow();
        });
        if (s.getNextAccountCode() == null) {
            Long max = repository.findMaxNumericCode(tenantId);
            s.setNextAccountCode(max == null ? FIRST_LEAF_CODE : max + 1);
        } else {
            // An import may have added higher numeric codes since we last handed one out.
            Long max = repository.findMaxNumericCode(tenantId);
            if (max != null && max >= s.getNextAccountCode()) {
                s.setNextAccountCode(max + 1);
            }
        }
        long code = s.getNextAccountCode();
        s.setNextAccountCode(code + 1);
        fiscalRepo.save(s);
        return Long.toString(code);
    }

    @Transactional
    public Account updateAccount(UUID id, Account updates) {
        Account existing = getAccountById(id);
        if (existing.isSystem()) {
            throw new BusinessRuleViolationException("System accounts cannot be modified");
        }
        existing.setName(updates.getName());
        existing.setNameEn(updates.getNameEn());
        existing.setNameAr(updates.getNameAr());
        existing.setAlias(updates.getAlias());
        existing.setDescription(updates.getDescription());
        existing.setAccountSubType(updates.getAccountSubType());
        existing.setActive(updates.isActive());
        existing.setDisplayOrder(updates.getDisplayOrder());
        existing.setProperty(updates.getProperty());
        return repository.save(existing);
    }

    @Transactional
    public void deleteAccount(UUID id) {
        Account account = getAccountById(id);
        if (account.isSystem()) {
            throw new BusinessRuleViolationException("System accounts cannot be deleted");
        }
        if (repository.existsByParent_Id(account.getId())) {
            throw new BusinessRuleViolationException("Cannot delete account with child accounts");
        }
        repository.delete(account);
    }

    /**
     * Seeds the default Chart of Accounts for a new tenant (PACT-shaped groups).
     * Only seeds if no accounts exist for the current tenant. Idempotent.
     */
    @Transactional
    public List<Account> seedDefaultAccounts() {
        List<Account> existing = repository.findAll();
        if (!existing.isEmpty()) {
            return existing;
        }
        Map<String, Account> byCode = new LinkedHashMap<>();
        // (code, nameEn, nameAr, type, subType, parentCode, description, isGroup)
        seed(byCode, "A", "Assets", "الأصول", AccountType.ASSET, AccountSubType.OTHER_ASSET, null, "All assets", true);
        seed(byCode, "A-01", "Fixed Assets", "الأصول الثابتة", AccountType.ASSET, AccountSubType.FIXED_ASSET, "A", "Office equipment, AC units, other capital items", true);
        seed(byCode, "A-02", "Current Assets", "الأصول المتداولة", AccountType.ASSET, AccountSubType.OTHER_ASSET, "A", "Short-term assets", true);
        seed(byCode, "A-02-01", "Rental Receivable A/c", "إيجارات مستحقة", AccountType.ASSET, AccountSubType.RECEIVABLE, "A-02", "Rent due from tenants, one leaf per property", true);
        seed(byCode, "A-02-02", "Bank", "الحسابات البنكية", AccountType.ASSET, AccountSubType.BANK, "A-02", "Bank accounts, one leaf per property", true);
        seed(byCode, "A-02-03", "PDCs", "شيكات مؤجلة مستحقة", AccountType.ASSET, AccountSubType.PDC_RECEIVABLE, "A-02", "Post-dated cheques held from tenants, one leaf per property", true);
        seed(byCode, "A-02-04", "Input VAT", "ضريبة المدخلات", AccountType.ASSET, AccountSubType.OTHER_ASSET, "A-02", "VAT paid on purchases", true);
        seed(byCode, "A-02-04-001", "Input VAT on Purchases", "ضريبة المدخلات على المشتريات", AccountType.ASSET, AccountSubType.OTHER_ASSET, "A-02-04", null, false);
        seed(byCode, "A-02-05", "Cash Group", "النقد", AccountType.ASSET, AccountSubType.CASH, "A-02", "Cash in hand and petty cash", true);
        seed(byCode, "A-02-05-001", "Cash Account", "حساب النقد", AccountType.ASSET, AccountSubType.CASH, "A-02-05", null, false);

        seed(byCode, "B", "Liability", "الالتزامات", AccountType.LIABILITY, AccountSubType.OTHER_LIABILITY, null, "All liabilities", true);
        seed(byCode, "B-01", "Current Liability", "الالتزامات المتداولة", AccountType.LIABILITY, AccountSubType.OTHER_LIABILITY, "B", null, true);
        seed(byCode, "B-01-01", "Advance Rent Group", "الإيجار المقدم", AccountType.LIABILITY, AccountSubType.ADVANCE, "B-01", "Unearned rent, one leaf per property", true);
        seed(byCode, "B-01-02", "Security Deposits", "التأمينات", AccountType.LIABILITY, AccountSubType.DEPOSIT_HELD, "B-01", "Deposits held, one leaf per property", true);
        seed(byCode, "B-01-03", "Output VAT", "ضريبة المخرجات", AccountType.LIABILITY, AccountSubType.OTHER_LIABILITY, "B-01", null, true);
        seed(byCode, "B-01-03-001", "Output VAT on Sales", "ضريبة المخرجات على المبيعات", AccountType.LIABILITY, AccountSubType.OTHER_LIABILITY, "B-01-03", null, false);
        seed(byCode, "B-01-04", "Vendors", "الموردون", AccountType.LIABILITY, AccountSubType.PAYABLE, "B-01", "One leaf per vendor, created with the vendor", true);
        seed(byCode, "B-02", "PDC Payables", "شيكات مؤجلة مستحقة الدفع", AccountType.LIABILITY, AccountSubType.OTHER_LIABILITY, "B", null, true);

        seed(byCode, "C", "Income", "الإيرادات", AccountType.INCOME, AccountSubType.OTHER_INCOME, null, "All income", true);
        seed(byCode, "C-01", "Direct Income", "الإيرادات المباشرة", AccountType.INCOME, AccountSubType.OTHER_INCOME, "C", null, true);
        seed(byCode, "C-01-01", "Rental Income Group", "إيرادات الإيجار", AccountType.INCOME, AccountSubType.RENTAL_INCOME, "C-01", "Rental income and admin fees, one leaf per property", true);
        seed(byCode, "C-01-02", "Other Income", "إيرادات أخرى", AccountType.INCOME, AccountSubType.OTHER_INCOME, "C-01", "Penalties, maintenance charges, forfeitures", true);
        seed(byCode, "C-01-02-001", "Amount Forfeited", "مبالغ مصادرة", AccountType.INCOME, AccountSubType.OTHER_INCOME, "C-01-02", null, false);
        seed(byCode, "C-02", "Indirect Income", "إيرادات غير مباشرة", AccountType.INCOME, AccountSubType.OTHER_INCOME, "C", null, true);

        seed(byCode, "D", "Expense", "المصروفات", AccountType.EXPENSE, AccountSubType.OTHER_EXPENSE, null, "All expenses", true);
        seed(byCode, "D-01", "Direct Expense", "المصروفات المباشرة", AccountType.EXPENSE, AccountSubType.OTHER_EXPENSE, "D", "Building running costs, one leaf per property per category", true);
        seed(byCode, "D-02", "Indirect Expense", "المصروفات غير المباشرة", AccountType.EXPENSE, AccountSubType.OTHER_EXPENSE, "D", null, true);
        seed(byCode, "D-02-001", "Rounding Off", "فروق التقريب", AccountType.EXPENSE, AccountSubType.OTHER_EXPENSE, "D-02", null, false);
        seed(byCode, "D-02-002", "Discount Allowed", "خصم مسموح", AccountType.EXPENSE, AccountSubType.OTHER_EXPENSE, "D-02", null, false);
        seed(byCode, "D-02-003", "Bank Charges", "رسوم بنكية", AccountType.EXPENSE, AccountSubType.OTHER_EXPENSE, "D-02", null, false);

        seed(byCode, "F", "Equity", "حقوق الملكية", AccountType.EQUITY, AccountSubType.CAPITAL, null, null, true);
        seed(byCode, "F-01", "Capital Account", "حساب رأس المال", AccountType.EQUITY, AccountSubType.CAPITAL, "F", "Owner's capital account", false);
        seed(byCode, "F-02", "Opening Balance Difference", "فرق الأرصدة الافتتاحية", AccountType.EQUITY, AccountSubType.CAPITAL, "F", "Suspense for an unbalanced opening-balance import; clear with a JV", false);

        return repository.saveAll(byCode.values());
    }

    private void seed(Map<String, Account> byCode, String code, String nameEn, String nameAr,
                      AccountType type, AccountSubType subType, String parentCode,
                      String description, boolean isGroup) {
        Account a = new Account();
        a.setCode(code);
        a.setName(nameEn);
        a.setNameEn(nameEn);
        a.setNameAr(nameAr);
        a.setAccountType(type);
        a.setAccountSubType(subType);
        a.setDescription(description);
        a.setSystem(true);
        a.setGroup(isGroup);
        if (parentCode != null) {
            Account parent = byCode.get(parentCode);
            if (parent == null) throw new IllegalStateException("Seed parent missing: " + parentCode);
            a.setParent(parent);
        }
        byCode.put(code, a);
    }
}
```

Check `AccountSubType` has every constant used above (`FIXED_ASSET, OTHER_ASSET, RECEIVABLE, BANK, PDC_RECEIVABLE, CASH, OTHER_LIABILITY, ADVANCE, DEPOSIT_HELD, PAYABLE, OTHER_INCOME, RENTAL_INCOME, OTHER_EXPENSE, CAPITAL`); if one is missing, add it to the enum rather than picking a different one. Remove the `AccountMappingService` constructor dependency (the class is deleted in Task 15; its `seedDefaults()` call goes now). Imports to add: `java.util.LinkedHashMap`, `java.util.Map`, `com.datagami.rentaxis.core.tenant.TenantContextHolder`, `com.datagami.rentaxis.domain.entity.TenantFiscalSettings`, `com.datagami.rentaxis.domain.entity.Property`, `com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository`.

- [ ] **Step 7: Update `AccountController`, `AccountImportService`, `UserRole`**

`AccountController`: class-level `@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")`. Add:

```java
    @GetMapping("/tree")
    public ResponseEntity<List<Account>> getTree() { return ResponseEntity.ok(service.getTree()); }

    @GetMapping("/{id}/children")
    public ResponseEntity<List<Account>> getChildren(@PathVariable UUID id) { return ResponseEntity.ok(service.getChildren(id)); }
```

`createAccount` binds the entity directly; because `parent` is `@JsonIgnore`, accept `parentId` via a small request record instead:

```java
    public record CreateAccountRequest(String code, String name, String nameEn, String nameAr, String alias,
                                       AccountType accountType, AccountSubType accountSubType, String description,
                                       UUID parentId, UUID propertyId, boolean group) {}

    @PostMapping
    public ResponseEntity<Account> createAccount(@RequestBody CreateAccountRequest r) {
        Account a = new Account();
        a.setCode(r.code()); a.setName(r.name() != null ? r.name() : r.nameEn()); a.setNameEn(r.nameEn()); a.setNameAr(r.nameAr());
        a.setAlias(r.alias()); a.setAccountType(r.accountType()); a.setAccountSubType(r.accountSubType());
        a.setDescription(r.description()); a.setGroup(r.group());
        if (r.parentId() != null) { Account p = new Account(); p.setId(r.parentId()); a.setParent(p); }
        if (r.propertyId() != null) { Property p = new Property(); p.setId(r.propertyId()); a.setProperty(p); }
        return ResponseEntity.ok(service.createAccount(a));
    }
```

Do the same for `updateAccount` (record `UpdateAccountRequest(name, nameEn, nameAr, alias, description, accountSubType, active, displayOrder, propertyId)` → populate an `Account` and call `service.updateAccount(id, a)`).

`AccountImportService`: both `importFromCsv` and `importFromExcel` currently set `parentCode` and `hierarchyLevel`. Change them to a two-pass import: first pass builds `Map<String, Account> byCode` from the file rows (setting `setSystem(false)`, `setGroup(...)`), second pass resolves each row's parent code against `byCode` **then** against `repository.findByCode(parentCode)` for parents that already exist in the tenant, and throws `IllegalArgumentException("Unknown parent code: " + parentCode + " on row " + n)` if neither matches. Save with `repository.saveAll(byCode.values())` (parents first is guaranteed because JPA cascades nothing here — so order the save: groups whose parent is null/already persisted first; simplest is to loop `saveAll` over rows sorted by depth computed from the in-memory map). Remove every reference to `setHierarchyLevel`.

`UserRole`: add `ACCOUNTANT` after `PROPERTY_MANAGER`. Grep for `switch (role)`/`UserRole.values()` usages that enumerate roles (`UserService`, `web` role labels come in Task 14) and make sure an exhaustive switch compiles.

- [ ] **Step 8: Fix compile fallout, run tests, commit**

Grep for `getParentCode`, `setParentCode`, `getHierarchyLevel`, `findByParentCode`, `existsByParentCode` across `backend/src` and update each call site to the `parent` relationship (`AccountDeletionService`, `AccountServiceTest`, `ChartOfAccountsFallbackCodesTest` are the likely ones). Where a test asserted `hierarchyLevel`, delete the assertion.

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.AccountServiceTreeIT' --tests 'com.datagami.rentaxis.domain.LedgerSchemaIT' --tests 'com.datagami.rentaxis.core.service.AccountServiceTest'`
Expected: PASS.

```bash
git add backend/src/main/resources/db/changelog backend/src/main/java backend/src/test/java
git commit -m "feat(ledger): journal schema, balance/immutability triggers, accounts tree by parent_id

Changeset 81 adds journal_entries/journal_lines with a deferred balance
constraint trigger, per-tenant entry sequences, fiscal settings, property
account mappings and the account template. accounts.parent_code becomes
parent_id; property_id and alias are added; the CoA seed builds the tree
in memory and no longer seeds v1 account_mappings.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Journal entities, enums, entry numbering, fiscal settings service

**Files:**
- Create: `domain/entity/enums/JournalDocType.java`, `JournalStatus.java`, `JournalSourceType.java`
- Create: `domain/entity/JournalEntry.java`, `JournalLine.java`, `JournalEntrySequence.java`
- Create: `domain/repository/JournalEntryRepository.java`, `JournalLineRepository.java`, `JournalEntrySequenceRepository.java`
- Create: `core/service/ledger/EntryNumberService.java`, `core/service/ledger/TenantFiscalSettingsService.java`
- Test: `core/service/ledger/EntryNumberServiceIT.java`, `core/service/ledger/TenantFiscalSettingsServiceTest.java`

**Interfaces:**
- Produces: `JournalEntry` (fields below), `JournalLine`, `EntryNumberService.next(JournalDocType, LocalDate) : String`, `TenantFiscalSettingsService.get() : TenantFiscalSettings`, `fiscalYearOf(LocalDate) : int`, `assertOpen(LocalDate)`, `lockThrough(LocalDate)`, `setBooksStartDate(LocalDate)`, `setFiscalYearStartMonth(int)`.
- Consumes: Task 1 tables, Task 2 `TenantFiscalSettings` + repository.

- [ ] **Step 1: Enums**

```java
package com.datagami.rentaxis.domain.entity.enums;

/** Journal number prefixes. Match PACT's vocabulary so the client's accountant recognises them (spec §3). */
public enum JournalDocType {
    TCO,  // tenancy contract posting
    TCR,  // contract reversal / unearned rent on termination
    PDR,  // PDC registered
    CRT,  // cheque cleared / receipt realised
    CBR,  // cheque bounced / returned
    CIL,  // monthly income recognition
    RCP,  // cash/online receipt
    STL,  // settlement
    PEN,  // penalty
    PISR, // purchase / service invoice
    BPV,  // bank / cash payment voucher
    OB,   // opening balance
    JV    // manual journal
}
```

```java
package com.datagami.rentaxis.domain.entity.enums;

public enum JournalStatus { POSTED, REVERSED }
```

```java
package com.datagami.rentaxis.domain.entity.enums;

public enum JournalSourceType {
    LEASE, CHEQUE, RECOGNITION, PENALTY, SETTLEMENT, VOUCHER, OPENING_BALANCE, IMPORT, MANUAL, REVERSAL
}
```

- [ ] **Step 2: Entities**

`domain/entity/JournalEntry.java`:

```java
package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Immutable after insert. The database forbids UPDATE of every column except
 * status/reversed_by_id and forbids DELETE (changeset 81). Never expose setters
 * through an API; the only mutation path is PostingService.reverse().
 */
@Entity
@Table(name = "journal_entries")
@Getter
@Setter
public class JournalEntry extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "entry_number", nullable = false, length = 40)
    private String entryNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 10)
    private JournalDocType docType;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(columnDefinition = "text")
    private String narration;

    @Column(name = "property_id") private UUID propertyId;
    @Column(name = "unit_id") private UUID unitId;
    @Column(name = "lease_id") private UUID leaseId;
    @Column(name = "renter_id") private UUID renterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 30)
    private JournalSourceType sourceType;

    @Column(name = "source_id") private UUID sourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private JournalStatus status = JournalStatus.POSTED;

    @Column(name = "reversal_of_id") private UUID reversalOfId;
    @Column(name = "reversed_by_id") private UUID reversedById;
    @Column(name = "import_batch_id") private UUID importBatchId;
    @Column(name = "posted_by") private UUID postedBy;
    @Column(name = "posted_at") private Instant postedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    @OrderBy("lineNo ASC")
    private List<JournalLine> lines = new ArrayList<>();

    public void addLine(JournalLine line) {
        line.setEntry(this);
        line.setLineNo(lines.size() + 1);
        lines.add(line);
    }
}
```

`domain/entity/JournalLine.java`:

```java
package com.datagami.rentaxis.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "journal_lines")
@Getter
@Setter
public class JournalLine extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    @JsonIgnore
    private JournalEntry entry;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    @JsonIgnore
    private Account account;

    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public UUID getAccountId() { return account == null ? null : account.getId(); }

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal credit = BigDecimal.ZERO;

    @Column(name = "property_id") private UUID propertyId;
    @Column(name = "unit_id") private UUID unitId;
    @Column(name = "lease_id") private UUID leaseId;
    @Column(name = "renter_id") private UUID renterId;
    @Column(name = "cheque_id") private UUID chequeId;

    @Column(columnDefinition = "text")
    private String narration;
}
```

`domain/entity/JournalEntrySequence.java` (composite key, not tenant-filtered — looked up explicitly and locked):

```java
package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "journal_entry_sequences")
@IdClass(JournalEntrySequence.Key.class)
@Getter
@Setter
public class JournalEntrySequence {

    @Id @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Id @Column(name = "doc_type", nullable = false, length = 10) private String docType;
    @Id @Column(name = "fiscal_year", nullable = false) private int fiscalYear;

    @Column(name = "next_value", nullable = false)
    private long nextValue = 1;

    @Getter @Setter
    public static class Key implements Serializable {
        private UUID tenantId;
        private String docType;
        private int fiscalYear;
        public Key() {}
        public Key(UUID tenantId, String docType, int fiscalYear) { this.tenantId = tenantId; this.docType = docType; this.fiscalYear = fiscalYear; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Key k)) return false;
            return Objects.equals(tenantId, k.tenantId) && Objects.equals(docType, k.docType) && fiscalYear == k.fiscalYear;
        }
        @Override public int hashCode() { return Objects.hash(tenantId, docType, fiscalYear); }
    }
}
```

- [ ] **Step 3: Repositories**

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    List<JournalEntry> findBySourceTypeAndSourceIdOrderByEntryDateAscCreatedAtAsc(JournalSourceType sourceType, UUID sourceId);

    @Query("""
        select e from JournalEntry e
        where (:docType is null or e.docType = :docType)
          and (:from is null or e.entryDate >= :from)
          and (:to is null or e.entryDate <= :to)
          and (:propertyId is null or e.propertyId = :propertyId)
          and (:leaseId is null or e.leaseId = :leaseId)
        order by e.entryDate desc, e.createdAt desc
        """)
    Page<JournalEntry> search(JournalDocType docType, LocalDate from, LocalDate to, UUID propertyId, UUID leaseId, Pageable pageable);

    List<JournalEntry> findByImportBatchIdOrderByCreatedAtAsc(UUID importBatchId);
}
```

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.JournalLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JournalLineRepository extends JpaRepository<JournalLine, UUID> {
    List<JournalLine> findByEntry_IdOrderByLineNoAsc(UUID entryId);
    boolean existsByAccount_Id(UUID accountId);
}
```

(Ledger aggregation queries are added to this repository in Task 8.)

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.JournalEntrySequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface JournalEntrySequenceRepository extends JpaRepository<JournalEntrySequence, JournalEntrySequence.Key> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from JournalEntrySequence s where s.tenantId = :tenantId and s.docType = :docType and s.fiscalYear = :fiscalYear")
    Optional<JournalEntrySequence> lock(UUID tenantId, String docType, int fiscalYear);
}
```

- [ ] **Step 4: Write the failing tests**

`core/service/ledger/TenantFiscalSettingsServiceTest.java` (pure unit):

```java
package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TenantFiscalSettingsServiceTest {

    TenantFiscalSettingsRepository repo = mock(TenantFiscalSettingsRepository.class);
    TenantFiscalSettingsService service = new TenantFiscalSettingsService(repo);
    UUID tenant = UUID.randomUUID();

    @BeforeEach void ctx() { TenantContextHolder.setTenantId(tenant); when(repo.save(any())).thenAnswer(i -> i.getArgument(0)); }
    @AfterEach void clear() { TenantContextHolder.clear(); }

    private TenantFiscalSettings settings(int startMonth, LocalDate lockedThrough) {
        TenantFiscalSettings s = new TenantFiscalSettings();
        s.setTenantId(tenant); s.setFiscalYearStartMonth(startMonth); s.setBooksLockedThrough(lockedThrough);
        return s;
    }

    @Test
    void getCreatesDefaultsWhenMissing() {
        when(repo.findById(tenant)).thenReturn(Optional.empty());
        TenantFiscalSettings s = service.get();
        assertThat(s.getFiscalYearStartMonth()).isEqualTo(1);
        verify(repo).save(any());
    }

    @Test
    void fiscalYearIsCalendarYearWhenStartMonthIsJanuary() {
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, null)));
        assertThat(service.fiscalYearOf(LocalDate.of(2026, 9, 24))).isEqualTo(2026);
        assertThat(service.fiscalYearOf(LocalDate.of(2026, 1, 1))).isEqualTo(2026);
    }

    @Test
    void fiscalYearIsTheYearTheFiscalYearStartsWhenStartMonthIsJune() {
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(6, null)));
        assertThat(service.fiscalYearOf(LocalDate.of(2026, 9, 24))).isEqualTo(2026);
        assertThat(service.fiscalYearOf(LocalDate.of(2026, 5, 31))).isEqualTo(2025);
    }

    @Test
    void assertOpenRejectsDatesOnOrBeforeTheLock() {
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, LocalDate.of(2026, 8, 31))));
        assertThatThrownBy(() -> service.assertOpen(LocalDate.of(2026, 8, 31)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("locked through 2026-08-31");
        service.assertOpen(LocalDate.of(2026, 9, 1)); // no throw
    }

    @Test
    void assertOpenPassesWhenNoLockIsSet() {
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, null)));
        service.assertOpen(LocalDate.of(2000, 1, 1));
    }

    @Test
    void lockThroughCannotMoveBackwards() {
        when(repo.findById(tenant)).thenReturn(Optional.of(settings(1, LocalDate.of(2026, 8, 31))));
        assertThatThrownBy(() -> service.lockThrough(LocalDate.of(2026, 7, 31)))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(service.lockThrough(LocalDate.of(2026, 9, 30)).getBooksLockedThrough()).isEqualTo(LocalDate.of(2026, 9, 30));
    }
}
```

`core/service/ledger/EntryNumberServiceIT.java`:

```java
package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class EntryNumberServiceIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired EntryNumberService service;
    @Autowired LandlordOrgRepository orgRepo;
    UUID tenantId;

    @BeforeEach void tenant() {
        LandlordOrg org = new LandlordOrg(); org.setName("Seq-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
    }
    @AfterEach void clear() { TenantContextHolder.clear(); }

    @Test
    void numbersArePrefixYearSlashSequencePerDocType() {
        assertThat(service.next(JournalDocType.TCO, LocalDate.of(2026, 9, 16))).isEqualTo("TCO-26/1");
        assertThat(service.next(JournalDocType.TCO, LocalDate.of(2026, 9, 17))).isEqualTo("TCO-26/2");
        assertThat(service.next(JournalDocType.PDR, LocalDate.of(2026, 9, 17))).isEqualTo("PDR-26/1");
        assertThat(service.next(JournalDocType.TCO, LocalDate.of(2027, 1, 5))).isEqualTo("TCO-27/1");
    }

    @Test
    void concurrentCallersNeverShareANumber() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<String>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            futures.add(pool.submit(() -> {
                TenantContextHolder.setTenantId(tenantId);
                try { return service.next(JournalDocType.JV, LocalDate.of(2026, 9, 17)); }
                finally { TenantContextHolder.clear(); }
            }));
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Future<String> f : futures) seen.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(seen).hasSize(40);
    }
}
```

- [ ] **Step 5: Run to verify they fail**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.ledger.*'`
Expected: compile errors (services missing).

- [ ] **Step 6: Implement the services**

`core/service/ledger/TenantFiscalSettingsService.java`:

```java
package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Service
public class TenantFiscalSettingsService {

    private final TenantFiscalSettingsRepository repo;

    public TenantFiscalSettingsService(TenantFiscalSettingsRepository repo) { this.repo = repo; }

    /** Settings for the current tenant; a default row is created on first access. */
    @Transactional
    public TenantFiscalSettings get() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) throw new IllegalStateException("No tenant in context");
        return repo.findById(tenantId).orElseGet(() -> {
            TenantFiscalSettings s = new TenantFiscalSettings();
            s.setTenantId(tenantId);
            return repo.save(s);
        });
    }

    /** The fiscal year is labelled by the calendar year in which it starts. */
    @Transactional(readOnly = true)
    public int fiscalYearOf(LocalDate date) {
        int startMonth = get().getFiscalYearStartMonth();
        return date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1;
    }

    /** Throws when {@code date} falls in a locked period. Import and OB postings bypass this in PostingService. */
    @Transactional(readOnly = true)
    public void assertOpen(LocalDate date) {
        LocalDate locked = get().getBooksLockedThrough();
        if (locked != null && !date.isAfter(locked)) {
            throw new BusinessRuleViolationException(
                    "Cannot post on " + date + ": books are locked through " + locked);
        }
    }

    @Transactional
    public TenantFiscalSettings lockThrough(LocalDate date) {
        TenantFiscalSettings s = get();
        if (s.getBooksLockedThrough() != null && date.isBefore(s.getBooksLockedThrough())) {
            throw new BusinessRuleViolationException("Period lock cannot move backwards (currently " + s.getBooksLockedThrough() + ")");
        }
        s.setBooksLockedThrough(date);
        return repo.save(s);
    }

    @Transactional
    public TenantFiscalSettings setBooksStartDate(LocalDate date) {
        TenantFiscalSettings s = get();
        s.setBooksStartDate(date);
        if (s.getBooksLockedThrough() == null) s.setBooksLockedThrough(date.minusDays(1));
        return repo.save(s);
    }

    @Transactional
    public TenantFiscalSettings setFiscalYearStartMonth(int month) {
        if (month < 1 || month > 12) throw new BusinessRuleViolationException("Fiscal year start month must be 1-12");
        TenantFiscalSettings s = get();
        s.setFiscalYearStartMonth(month);
        return repo.save(s);
    }
}
```

`core/service/ledger/EntryNumberService.java`:

```java
package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.JournalEntrySequence;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.repository.JournalEntrySequenceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Hands out "TCO-26/1629"-style numbers: doc type, two-digit fiscal year,
 * per-tenant per-type per-year counter. Runs in the caller's transaction so
 * a rolled-back posting releases its number (gaps are acceptable; duplicates
 * are not — the row lock guarantees that).
 */
@Service
public class EntryNumberService {

    private final JournalEntrySequenceRepository repo;
    private final TenantFiscalSettingsService fiscal;

    public EntryNumberService(JournalEntrySequenceRepository repo, TenantFiscalSettingsService fiscal) {
        this.repo = repo;
        this.fiscal = fiscal;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public String next(JournalDocType docType, LocalDate entryDate) {
        UUID tenantId = TenantContextHolder.getTenantId();
        int fy = fiscal.fiscalYearOf(entryDate);
        JournalEntrySequence seq = repo.lock(tenantId, docType.name(), fy).orElseGet(() -> create(tenantId, docType, fy));
        long value = seq.getNextValue();
        seq.setNextValue(value + 1);
        repo.save(seq);
        return docType.name() + "-" + String.format("%02d", fy % 100) + "/" + value;
    }

    private JournalEntrySequence create(UUID tenantId, JournalDocType docType, int fy) {
        JournalEntrySequence s = new JournalEntrySequence();
        s.setTenantId(tenantId);
        s.setDocType(docType.name());
        s.setFiscalYear(fy);
        try {
            repo.saveAndFlush(s);
        } catch (DataIntegrityViolationException raced) {
            // Another transaction created it first; fall through to the locked read.
        }
        return repo.lock(tenantId, docType.name(), fy).orElseThrow();
    }
}
```

Note: the `create` race path needs the insert to happen outside the current transaction to survive a PK collision in Postgres (the failed insert aborts the transaction). Put `create` in a separate `@Service` method with `@Transactional(propagation = Propagation.REQUIRES_NEW)` — self-invocation does not proxy, so either inject a second bean (`EntryNumberSequenceCreator`) or call `repo.saveAndFlush` via `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW`. Use `TransactionTemplate` (inject `PlatformTransactionManager`), and swallow `DataIntegrityViolationException` inside it.

- [ ] **Step 7: Run tests, commit**

Run: `cd backend && ./gradlew test --tests 'com.datagami.rentaxis.core.service.ledger.*'`
Expected: PASS (the concurrency test proves the lock).

```bash
git add backend/src
git commit -m "feat(ledger): journal entities, entry numbering, fiscal settings with period lock

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: AccountRole, mappings, AccountResolver

**Files:**
- Create: `domain/entity/enums/AccountRole.java`
- Create: `domain/entity/PropertyAccountMapping.java`, `TenantDefaultAccountMapping.java`, `PropertyAccountTemplateRow.java`
- Create: `domain/repository/PropertyAccountMappingRepository.java`, `TenantDefaultAccountMappingRepository.java`, `PropertyAccountTemplateRowRepository.java`
- Create: `core/service/ledger/AccountResolver.java`, `core/service/ledger/UnmappedAccountRoleException.java`
- Test: `core/service/ledger/AccountResolverTest.java`

**Interfaces:**
- Produces: `AccountRole` enum; `AccountResolver.resolve(AccountRole, UUID propertyId) : Account`; `AccountResolver.resolveAll(Set<AccountRole>, UUID propertyId) : Map<AccountRole, Account>` (throws once with every missing role); `UnmappedAccountRoleException extends BusinessRuleViolationException` with `getMissingRoles()`.

- [ ] **Step 1: Enum + entities + repositories**

```java
package com.datagami.rentaxis.domain.entity.enums;

/** Abstract account purposes. Posting rules speak roles; AccountResolver maps (role, property) to a leaf. Spec §5.1. */
public enum AccountRole {
    RENT_RECEIVABLE, ADVANCE_RENT, RENTAL_INCOME, PDC_RECEIVABLE, BANK,
    SECURITY_DEPOSIT, ADMIN_FEE, PARKING_INCOME, PARKING_DEPOSIT, COOLING_CHARGES,
    MAINTENANCE_CHARGES, RENT_PENALTY, CHEQUE_RETURN_PENALTY, OTHER_INCOME,
    FORFEITED_INCOME, DISCOUNT_ALLOWED, ROUNDING_OFF, CASH, OUTPUT_VAT, INPUT_VAT,
    OPENING_BALANCE_DIFFERENCE;

    /** Roles that are normally per-property (template rows). The rest default to tenant-level mappings. */
    public boolean isPropertyScoped() {
        return switch (this) {
            case DISCOUNT_ALLOWED, ROUNDING_OFF, CASH, OUTPUT_VAT, INPUT_VAT, OPENING_BALANCE_DIFFERENCE -> false;
            default -> true;
        };
    }
}
```

```java
package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "property_account_mappings")
@Getter
@Setter
public class PropertyAccountMapping extends BaseTenantEntity {
    @Id @GeneratedValue(strategy = GenerationType.AUTO) private UUID id;
    @Column(name = "property_id", nullable = false) private UUID propertyId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private AccountRole role;
    @ManyToOne(fetch = FetchType.EAGER) @JoinColumn(name = "account_id", nullable = false) private Account account;
}
```

```java
package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "tenant_default_account_mappings")
@Getter
@Setter
public class TenantDefaultAccountMapping extends BaseTenantEntity {
    @Id @GeneratedValue(strategy = GenerationType.AUTO) private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private AccountRole role;
    @ManyToOne(fetch = FetchType.EAGER) @JoinColumn(name = "account_id", nullable = false) private Account account;
}
```

```java
package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "property_account_template_rows")
@Getter
@Setter
public class PropertyAccountTemplateRow extends BaseTenantEntity {
    @Id @GeneratedValue(strategy = GenerationType.AUTO) private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private AccountRole role;
    /** e.g. "Rent Receivable - {property}"; {property} is replaced with Property.nameEn. */
    @Column(name = "name_pattern", nullable = false) private String namePattern;
    @ManyToOne(fetch = FetchType.EAGER) @JoinColumn(name = "parent_account_id", nullable = false) private Account parentAccount;
    @Column(nullable = false) private boolean enabled = true;
}
```

Repositories (all in `domain/repository`):

```java
@Repository
public interface PropertyAccountMappingRepository extends JpaRepository<PropertyAccountMapping, UUID> {
    Optional<PropertyAccountMapping> findByPropertyIdAndRole(UUID propertyId, AccountRole role);
    List<PropertyAccountMapping> findByPropertyId(UUID propertyId);
    List<PropertyAccountMapping> findByPropertyIdAndRoleIn(UUID propertyId, Collection<AccountRole> roles);
    boolean existsByAccount_Id(UUID accountId);
}

@Repository
public interface TenantDefaultAccountMappingRepository extends JpaRepository<TenantDefaultAccountMapping, UUID> {
    Optional<TenantDefaultAccountMapping> findByRole(AccountRole role);
    List<TenantDefaultAccountMapping> findByRoleIn(Collection<AccountRole> roles);
    List<TenantDefaultAccountMapping> findAllByOrderByRoleAsc();
}

@Repository
public interface PropertyAccountTemplateRowRepository extends JpaRepository<PropertyAccountTemplateRow, UUID> {
    List<PropertyAccountTemplateRow> findAllByOrderByRoleAsc();
    List<PropertyAccountTemplateRow> findByEnabledTrue();
    Optional<PropertyAccountTemplateRow> findByRole(AccountRole role);
}
```

- [ ] **Step 2: Failing resolver test**

```java
package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.PropertyAccountMapping;
import com.datagami.rentaxis.domain.entity.TenantDefaultAccountMapping;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.repository.PropertyAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.TenantDefaultAccountMappingRepository;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountResolverTest {

    PropertyAccountMappingRepository propertyRepo = mock(PropertyAccountMappingRepository.class);
    TenantDefaultAccountMappingRepository defaultRepo = mock(TenantDefaultAccountMappingRepository.class);
    AccountResolver resolver = new AccountResolver(propertyRepo, defaultRepo);
    UUID property = UUID.randomUUID();

    private Account acct(String code) { Account a = new Account(); a.setId(UUID.randomUUID()); a.setCode(code); a.setActive(true); return a; }
    private PropertyAccountMapping pm(AccountRole r, Account a) { PropertyAccountMapping m = new PropertyAccountMapping(); m.setPropertyId(property); m.setRole(r); m.setAccount(a); return m; }
    private TenantDefaultAccountMapping dm(AccountRole r, Account a) { TenantDefaultAccountMapping m = new TenantDefaultAccountMapping(); m.setRole(r); m.setAccount(a); return m; }

    @Test
    void propertyMappingWins() {
        Account prop = acct("166269"), def = acct("105590");
        when(propertyRepo.findByPropertyIdAndRole(property, AccountRole.RENT_RECEIVABLE)).thenReturn(Optional.of(pm(AccountRole.RENT_RECEIVABLE, prop)));
        when(defaultRepo.findByRole(AccountRole.RENT_RECEIVABLE)).thenReturn(Optional.of(dm(AccountRole.RENT_RECEIVABLE, def)));
        assertThat(resolver.resolve(AccountRole.RENT_RECEIVABLE, property).getCode()).isEqualTo("166269");
    }

    @Test
    void fallsBackToTenantDefault() {
        Account def = acct("105590");
        when(propertyRepo.findByPropertyIdAndRole(property, AccountRole.RENT_RECEIVABLE)).thenReturn(Optional.empty());
        when(defaultRepo.findByRole(AccountRole.RENT_RECEIVABLE)).thenReturn(Optional.of(dm(AccountRole.RENT_RECEIVABLE, def)));
        assertThat(resolver.resolve(AccountRole.RENT_RECEIVABLE, property).getCode()).isEqualTo("105590");
    }

    @Test
    void nullPropertyUsesTenantDefaultOnly() {
        Account def = acct("C-01");
        when(defaultRepo.findByRole(AccountRole.OUTPUT_VAT)).thenReturn(Optional.of(dm(AccountRole.OUTPUT_VAT, def)));
        assertThat(resolver.resolve(AccountRole.OUTPUT_VAT, null).getCode()).isEqualTo("C-01");
    }

    @Test
    void unmappedRoleFailsLoudly() {
        when(propertyRepo.findByPropertyIdAndRole(any(), any())).thenReturn(Optional.empty());
        when(defaultRepo.findByRole(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolve(AccountRole.ADVANCE_RENT, property))
                .isInstanceOf(UnmappedAccountRoleException.class)
                .hasMessageContaining("ADVANCE_RENT");
    }

    @Test
    void inactiveAccountIsTreatedAsUnmapped() {
        Account dead = acct("1"); dead.setActive(false);
        when(propertyRepo.findByPropertyIdAndRole(property, AccountRole.BANK)).thenReturn(Optional.of(pm(AccountRole.BANK, dead)));
        when(defaultRepo.findByRole(AccountRole.BANK)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolve(AccountRole.BANK, property)).isInstanceOf(UnmappedAccountRoleException.class);
    }

    @Test
    void resolveAllReportsEveryMissingRoleAtOnce() {
        Account rr = acct("1");
        when(propertyRepo.findByPropertyIdAndRoleIn(eq(property), any())).thenReturn(List.of(pm(AccountRole.RENT_RECEIVABLE, rr)));
        when(defaultRepo.findByRoleIn(any())).thenReturn(List.of());
        assertThatThrownBy(() -> resolver.resolveAll(EnumSet.of(AccountRole.RENT_RECEIVABLE, AccountRole.PDC_RECEIVABLE, AccountRole.BANK), property))
                .isInstanceOf(UnmappedAccountRoleException.class)
                .satisfies(e -> assertThat(((UnmappedAccountRoleException) e).getMissingRoles())
                        .containsExactlyInAnyOrder(AccountRole.PDC_RECEIVABLE, AccountRole.BANK));
    }
}
```

- [ ] **Step 3: Run to verify it fails** — `./gradlew test --tests '*AccountResolverTest'` → compile error.

- [ ] **Step 4: Implement**

```java
package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class UnmappedAccountRoleException extends BusinessRuleViolationException {
    private final Set<AccountRole> missingRoles;
    private final UUID propertyId;

    public UnmappedAccountRoleException(Set<AccountRole> missingRoles, UUID propertyId) {
        super("No account mapped for role(s) " + missingRoles.stream().map(Enum::name).sorted().collect(Collectors.joining(", "))
                + (propertyId != null ? " on property " + propertyId : " (tenant default)")
                + ". Map them under Property > Accounts or Settings > Default accounts.");
        this.missingRoles = missingRoles;
        this.propertyId = propertyId;
    }
    public Set<AccountRole> getMissingRoles() { return missingRoles; }
    public UUID getPropertyId() { return propertyId; }
}
```

```java
package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.PropertyAccountMapping;
import com.datagami.rentaxis.domain.entity.TenantDefaultAccountMapping;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.repository.PropertyAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.TenantDefaultAccountMappingRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** (role, property) -> leaf account. Property mapping, then tenant default, then a hard error. Spec §4.4. */
@Component
public class AccountResolver {

    private final PropertyAccountMappingRepository propertyRepo;
    private final TenantDefaultAccountMappingRepository defaultRepo;

    public AccountResolver(PropertyAccountMappingRepository propertyRepo, TenantDefaultAccountMappingRepository defaultRepo) {
        this.propertyRepo = propertyRepo;
        this.defaultRepo = defaultRepo;
    }

    @Transactional(readOnly = true)
    public Account resolve(AccountRole role, UUID propertyId) {
        Account a = tryResolve(role, propertyId);
        if (a == null) throw new UnmappedAccountRoleException(EnumSet.of(role), propertyId);
        return a;
    }

    /** Resolves every role or throws once listing all that are missing — used by the lease posting guard. */
    @Transactional(readOnly = true)
    public Map<AccountRole, Account> resolveAll(Set<AccountRole> roles, UUID propertyId) {
        Map<AccountRole, Account> out = new EnumMap<>(AccountRole.class);
        if (propertyId != null) {
            for (PropertyAccountMapping m : propertyRepo.findByPropertyIdAndRoleIn(propertyId, roles)) {
                if (usable(m.getAccount())) out.put(m.getRole(), m.getAccount());
            }
        }
        Set<AccountRole> remaining = EnumSet.copyOf(roles);
        remaining.removeAll(out.keySet());
        if (!remaining.isEmpty()) {
            for (TenantDefaultAccountMapping m : defaultRepo.findByRoleIn(remaining)) {
                if (usable(m.getAccount())) out.put(m.getRole(), m.getAccount());
            }
        }
        remaining.removeAll(out.keySet());
        if (!remaining.isEmpty()) throw new UnmappedAccountRoleException(remaining, propertyId);
        return out;
    }

    private Account tryResolve(AccountRole role, UUID propertyId) {
        if (propertyId != null) {
            Account a = propertyRepo.findByPropertyIdAndRole(propertyId, role).map(PropertyAccountMapping::getAccount).orElse(null);
            if (usable(a)) return a;
        }
        Account d = defaultRepo.findByRole(role).map(TenantDefaultAccountMapping::getAccount).orElse(null);
        return usable(d) ? d : null;
    }

    private static boolean usable(Account a) { return a != null && a.isActive() && !a.isGroup(); }
}
```

- [ ] **Step 5: Run, commit**

Run: `./gradlew test --tests '*AccountResolverTest'` → PASS.

```bash
git add backend/src
git commit -m "feat(ledger): account roles, property/tenant mappings, AccountResolver

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: PostingService — post() and reverse()

**Files:**
- Create: `core/service/ledger/PostingRequest.java` (records), `core/service/ledger/PostingService.java`
- Test: `core/service/ledger/PostingServiceIT.java`

**Interfaces:**
- Produces:
  ```java
  public record PostingRequest(JournalDocType docType, LocalDate entryDate, String narration, Dimensions dims,
                               JournalSourceType sourceType, UUID sourceId, UUID importBatchId, List<Line> lines) {
      public record Dimensions(UUID propertyId, UUID unitId, UUID leaseId, UUID renterId, UUID chequeId) {
          public static Dimensions none() {...}  public static Dimensions ofProperty(UUID p) {...}
      }
      public sealed interface AccountRef permits ByRole, ById {}
      public record ByRole(AccountRole role) implements AccountRef {}   // resolved against dims.propertyId()
      public record ById(UUID accountId) implements AccountRef {}
      public enum Side { DR, CR }
      public record Line(AccountRef account, Side side, BigDecimal amount, Dimensions dims, String narration) {}
      // builders
      public static Line dr(AccountRole role, BigDecimal amount) / cr(AccountRole role, BigDecimal amount)
      public static Line dr(UUID accountId, BigDecimal amount) / cr(UUID accountId, BigDecimal amount)
      public Line withDims(Dimensions d) / withNarration(String n)   (on Line)
  }
  JournalEntry PostingService.post(PostingRequest r)
  JournalEntry PostingService.reverse(UUID entryId, LocalDate date, String reason)
  ```
- Consumes: `EntryNumberService.next`, `TenantFiscalSettingsService.assertOpen`, `AccountResolver.resolve`, `AccountRepository`, `JournalEntryRepository`.

- [ ] **Step 1: Failing integration test**

`core/service/ledger/PostingServiceIT.java`:

```java
package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.core.service.ledger.PostingRequest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class PostingServiceIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired PostingService posting;
    @Autowired AccountService accounts;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired JournalEntryRepository entries;
    @Autowired JournalLineRepository lines;
    @Autowired TenantDefaultAccountMappingRepository defaults;
    @Autowired PropertyAccountMappingRepository propertyMappings;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PropertyRepository propertyRepo;

    UUID tenantId; UUID propertyId;
    Account rentRecvLeaf, advanceRentLeaf, bankLeaf;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg(); org.setName("Post-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        Property p = new Property(); p.setNameEn("L'Olivier"); p.setEmirate(Emirate.DUBAI);
        propertyId = propertyRepo.save(p).getId();
        rentRecvLeaf = accounts.createLeaf("Rent Receivable - L'Olivier", accounts.getAccountByCode("A-02-01"), propertyId);
        advanceRentLeaf = accounts.createLeaf("Advance Rent - L'Olivier", accounts.getAccountByCode("B-01-01"), propertyId);
        bankLeaf = accounts.createLeaf("Emirates Islamic - L'Olivier", accounts.getAccountByCode("A-02-02"), propertyId);
        map(propertyId, AccountRole.RENT_RECEIVABLE, rentRecvLeaf);
        map(propertyId, AccountRole.ADVANCE_RENT, advanceRentLeaf);
        map(propertyId, AccountRole.BANK, bankLeaf);
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    private void map(UUID prop, AccountRole role, Account a) {
        PropertyAccountMapping m = new PropertyAccountMapping(); m.setPropertyId(prop); m.setRole(role); m.setAccount(a);
        propertyMappings.save(m);
    }

    private PostingRequest contract(BigDecimal amount) {
        return new PostingRequest(JournalDocType.TCO, LocalDate.of(2026, 9, 11), "Contract L'Olivier OLV-324",
                Dimensions.ofProperty(propertyId), JournalSourceType.LEASE, UUID.randomUUID(), null,
                List.of(dr(AccountRole.RENT_RECEIVABLE, amount), cr(AccountRole.ADVANCE_RENT, amount)));
    }

    @Test
    void postsBalancedEntryWithResolvedAccountsAndNumber() {
        JournalEntry e = posting.post(contract(new BigDecimal("61000.00")));
        assertThat(e.getEntryNumber()).isEqualTo("TCO-26/1");
        assertThat(e.getStatus()).isEqualTo(JournalStatus.POSTED);
        List<JournalLine> ls = lines.findByEntry_IdOrderByLineNoAsc(e.getId());
        assertThat(ls).hasSize(2);
        assertThat(ls.get(0).getAccountId()).isEqualTo(rentRecvLeaf.getId());
        assertThat(ls.get(0).getDebit()).isEqualByComparingTo("61000.00");
        assertThat(ls.get(1).getAccountId()).isEqualTo(advanceRentLeaf.getId());
        assertThat(ls.get(1).getCredit()).isEqualByComparingTo("61000.00");
        assertThat(ls.get(0).getPropertyId()).isEqualTo(propertyId); // header dims copied to lines
    }

    @Test
    void rejectsUnbalancedBeforeTouchingTheDatabase() {
        PostingRequest bad = new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 9, 11), "bad", Dimensions.ofProperty(propertyId),
                JournalSourceType.MANUAL, null, null,
                List.of(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("100")), cr(AccountRole.ADVANCE_RENT, new BigDecimal("99"))));
        assertThatThrownBy(() -> posting.post(bad)).isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("not balanced");
        assertThat(entries.count()).isZero();
    }

    @Test
    void rejectsZeroNegativeAndSingleLine() {
        assertThatThrownBy(() -> posting.post(contract(BigDecimal.ZERO))).hasMessageContaining("positive");
        assertThatThrownBy(() -> posting.post(contract(new BigDecimal("-5")))).hasMessageContaining("positive");
        PostingRequest one = new PostingRequest(JournalDocType.JV, LocalDate.now(), "one", Dimensions.none(), JournalSourceType.MANUAL, null, null,
                List.of(dr(bankLeaf.getId(), new BigDecimal("10"))));
        assertThatThrownBy(() -> posting.post(one)).hasMessageContaining("at least two lines");
    }

    @Test
    void rejectsGroupAccountsAndInactiveAccounts() {
        Account group = accounts.getAccountByCode("A-02-01");
        PostingRequest r = new PostingRequest(JournalDocType.JV, LocalDate.now(), "grp", Dimensions.none(), JournalSourceType.MANUAL, null, null,
                List.of(dr(group.getId(), new BigDecimal("10")), cr(bankLeaf.getId(), new BigDecimal("10"))));
        assertThatThrownBy(() -> posting.post(r)).hasMessageContaining("group account");
    }

    @Test
    void unmappedRoleFailsWithRoleName() {
        PostingRequest r = new PostingRequest(JournalDocType.JV, LocalDate.now(), "x", Dimensions.ofProperty(propertyId), JournalSourceType.MANUAL, null, null,
                List.of(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("10")), cr(AccountRole.SECURITY_DEPOSIT, new BigDecimal("10"))));
        assertThatThrownBy(() -> posting.post(r)).isInstanceOf(UnmappedAccountRoleException.class).hasMessageContaining("SECURITY_DEPOSIT");
    }

    @Test
    void periodLockBlocksOrdinaryPostingsButNotOpeningBalancesOrImports() {
        fiscal.lockThrough(LocalDate.of(2026, 9, 30));
        assertThatThrownBy(() -> posting.post(contract(new BigDecimal("10")))).hasMessageContaining("locked through 2026-09-30");
        PostingRequest ob = new PostingRequest(JournalDocType.OB, LocalDate.of(2026, 9, 11), "opening", Dimensions.none(), JournalSourceType.OPENING_BALANCE, null, null,
                List.of(dr(bankLeaf.getId(), new BigDecimal("10")), cr(accounts.getAccountByCode("F-01").getId(), new BigDecimal("10"))));
        assertThat(posting.post(ob).getEntryNumber()).startsWith("OB-26/");
        PostingRequest imported = new PostingRequest(JournalDocType.TCO, LocalDate.of(2026, 9, 11), "imported", Dimensions.ofProperty(propertyId),
                JournalSourceType.IMPORT, UUID.randomUUID(), UUID.randomUUID(),
                List.of(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("10")), cr(AccountRole.ADVANCE_RENT, new BigDecimal("10"))));
        assertThat(posting.post(imported).getImportBatchId()).isNotNull();
    }

    @Test
    void reverseCreatesMirrorAndLinksBoth() {
        JournalEntry e = posting.post(contract(new BigDecimal("61000.00")));
        JournalEntry rev = posting.reverse(e.getId(), LocalDate.of(2026, 9, 12), "wrong unit");
        assertThat(rev.getDocType()).isEqualTo(JournalDocType.TCR);
        assertThat(rev.getReversalOfId()).isEqualTo(e.getId());
        assertThat(rev.getNarration()).contains("Reversal of TCO-26/1").contains("wrong unit");
        List<JournalLine> ls = lines.findByEntry_IdOrderByLineNoAsc(rev.getId());
        assertThat(ls.get(0).getAccountId()).isEqualTo(rentRecvLeaf.getId());
        assertThat(ls.get(0).getCredit()).isEqualByComparingTo("61000.00");
        assertThat(ls.get(1).getDebit()).isEqualByComparingTo("61000.00");
        JournalEntry original = entries.findById(e.getId()).orElseThrow();
        assertThat(original.getStatus()).isEqualTo(JournalStatus.REVERSED);
        assertThat(original.getReversedById()).isEqualTo(rev.getId());
    }

    @Test
    void cannotReverseTwiceOrReverseAReversal() {
        JournalEntry e = posting.post(contract(new BigDecimal("10")));
        JournalEntry rev = posting.reverse(e.getId(), LocalDate.of(2026, 9, 12), "r");
        assertThatThrownBy(() -> posting.reverse(e.getId(), LocalDate.of(2026, 9, 13), "again")).hasMessageContaining("already reversed");
        assertThatThrownBy(() -> posting.reverse(rev.getId(), LocalDate.of(2026, 9, 13), "again")).hasMessageContaining("reversal entry");
    }

    @Test
    void amountsAreNormalisedToTwoDecimals() {
        PostingRequest r = new PostingRequest(JournalDocType.JV, LocalDate.now(), "scale", Dimensions.none(), JournalSourceType.MANUAL, null, null,
                List.of(dr(bankLeaf.getId(), new BigDecimal("978.0821")), cr(accounts.getAccountByCode("F-01").getId(), new BigDecimal("978.08"))));
        JournalEntry e = posting.post(r);
        assertThat(lines.findByEntry_IdOrderByLineNoAsc(e.getId()).get(0).getDebit()).isEqualByComparingTo("978.08");
    }
}
```

`Emirate.DUBAI` — check the enum constant name in `domain/entity/enums/Emirate.java` and use an existing one.

- [ ] **Step 2: Run to verify it fails** — compile error.

- [ ] **Step 3: Implement `PostingRequest`**

```java
package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Input to PostingService.post(). Callers name roles (resolved against dims.propertyId) or explicit account ids. */
public record PostingRequest(
        JournalDocType docType,
        LocalDate entryDate,
        String narration,
        Dimensions dims,
        JournalSourceType sourceType,
        UUID sourceId,
        UUID importBatchId,
        List<Line> lines) {

    public record Dimensions(UUID propertyId, UUID unitId, UUID leaseId, UUID renterId, UUID chequeId) {
        public static Dimensions none() { return new Dimensions(null, null, null, null, null); }
        public static Dimensions ofProperty(UUID propertyId) { return new Dimensions(propertyId, null, null, null, null); }
        /** Line dims override header dims field by field. */
        Dimensions mergedOver(Dimensions header) {
            if (header == null) return this;
            return new Dimensions(
                    propertyId != null ? propertyId : header.propertyId,
                    unitId != null ? unitId : header.unitId,
                    leaseId != null ? leaseId : header.leaseId,
                    renterId != null ? renterId : header.renterId,
                    chequeId != null ? chequeId : header.chequeId);
        }
    }

    public sealed interface AccountRef permits ByRole, ById {}
    public record ByRole(AccountRole role) implements AccountRef {}
    public record ById(UUID accountId) implements AccountRef {}

    public enum Side { DR, CR }

    public record Line(AccountRef account, Side side, BigDecimal amount, Dimensions dims, String narration) {
        public Line withDims(Dimensions d) { return new Line(account, side, amount, d, narration); }
        public Line withNarration(String n) { return new Line(account, side, amount, dims, n); }
    }

    public static Line dr(AccountRole role, BigDecimal amount) { return new Line(new ByRole(role), Side.DR, amount, null, null); }
    public static Line cr(AccountRole role, BigDecimal amount) { return new Line(new ByRole(role), Side.CR, amount, null, null); }
    public static Line dr(UUID accountId, BigDecimal amount) { return new Line(new ById(accountId), Side.DR, amount, null, null); }
    public static Line cr(UUID accountId, BigDecimal amount) { return new Line(new ById(accountId), Side.CR, amount, null, null); }
}
```

- [ ] **Step 4: Implement `PostingService`**

```java
package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.core.service.ledger.PostingRequest.*;

/**
 * The single write path into the ledger (spec §4.3). Validates, resolves roles,
 * numbers, and inserts one immutable JournalEntry. Corrections are reversals.
 */
@Service
public class PostingService {

    private final JournalEntryRepository entries;
    private final JournalLineRepository lines;
    private final AccountRepository accounts;
    private final AccountResolver resolver;
    private final EntryNumberService numbers;
    private final TenantFiscalSettingsService fiscal;

    public PostingService(JournalEntryRepository entries, JournalLineRepository lines, AccountRepository accounts,
                          AccountResolver resolver, EntryNumberService numbers, TenantFiscalSettingsService fiscal) {
        this.entries = entries; this.lines = lines; this.accounts = accounts;
        this.resolver = resolver; this.numbers = numbers; this.fiscal = fiscal;
    }

    @Transactional
    public JournalEntry post(PostingRequest r) {
        validateShape(r);
        if (r.docType() != JournalDocType.OB && r.importBatchId() == null) {
            fiscal.assertOpen(r.entryDate());
        }
        Dimensions header = r.dims() == null ? Dimensions.none() : r.dims();

        JournalEntry e = new JournalEntry();
        e.setDocType(r.docType());
        e.setEntryDate(r.entryDate());
        e.setNarration(r.narration());
        e.setPropertyId(header.propertyId()); e.setUnitId(header.unitId());
        e.setLeaseId(header.leaseId()); e.setRenterId(header.renterId());
        e.setSourceType(r.sourceType()); e.setSourceId(r.sourceId());
        e.setImportBatchId(r.importBatchId());
        e.setPostedBy(currentUserId());
        e.setPostedAt(Instant.now());
        e.setStatus(JournalStatus.POSTED);
        e.setEntryNumber(numbers.next(r.docType(), r.entryDate()));

        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        for (Line l : r.lines()) {
            BigDecimal amount = l.amount().setScale(2, RoundingMode.HALF_UP);
            if (amount.signum() <= 0) throw new BusinessRuleViolationException("Line amounts must be positive");
            Dimensions d = l.dims() == null ? header : l.dims().mergedOver(header);
            Account account = resolveAccount(l.account(), d.propertyId());
            if (account.isGroup()) throw new BusinessRuleViolationException("Cannot post to group account " + account.getCode());
            if (!account.isActive()) throw new BusinessRuleViolationException("Cannot post to inactive account " + account.getCode());

            JournalLine jl = new JournalLine();
            jl.setAccount(account);
            if (l.side() == Side.DR) { jl.setDebit(amount); dr = dr.add(amount); } else { jl.setCredit(amount); cr = cr.add(amount); }
            jl.setPropertyId(d.propertyId()); jl.setUnitId(d.unitId()); jl.setLeaseId(d.leaseId());
            jl.setRenterId(d.renterId()); jl.setChequeId(d.chequeId());
            jl.setNarration(l.narration());
            e.addLine(jl);
        }
        if (dr.compareTo(cr) != 0) {
            throw new BusinessRuleViolationException("Journal entry is not balanced: debit " + dr + " vs credit " + cr);
        }
        JournalEntry saved = entries.save(e);
        lines.saveAll(saved.getLines());
        return saved;
    }

    /** Mirror entry. Reverse doc type: TCO->TCR, everything else keeps its own type. */
    @Transactional
    public JournalEntry reverse(UUID entryId, LocalDate date, String reason) {
        JournalEntry original = entries.findById(entryId).orElseThrow(() -> new NotFoundException("Journal entry not found"));
        if (original.getReversalOfId() != null) throw new BusinessRuleViolationException("Cannot reverse a reversal entry");
        if (original.getStatus() == JournalStatus.REVERSED) throw new BusinessRuleViolationException("Entry " + original.getEntryNumber() + " is already reversed");
        if (original.getImportBatchId() == null) fiscal.assertOpen(date);

        JournalEntry rev = new JournalEntry();
        rev.setDocType(original.getDocType() == JournalDocType.TCO ? JournalDocType.TCR : original.getDocType());
        rev.setEntryDate(date);
        rev.setNarration("Reversal of " + original.getEntryNumber() + (reason == null || reason.isBlank() ? "" : ": " + reason));
        rev.setPropertyId(original.getPropertyId()); rev.setUnitId(original.getUnitId());
        rev.setLeaseId(original.getLeaseId()); rev.setRenterId(original.getRenterId());
        rev.setSourceType(JournalSourceType.REVERSAL); rev.setSourceId(original.getId());
        rev.setImportBatchId(original.getImportBatchId());
        rev.setReversalOfId(original.getId());
        rev.setPostedBy(currentUserId()); rev.setPostedAt(Instant.now());
        rev.setEntryNumber(numbers.next(rev.getDocType(), date));
        for (JournalLine ol : lines.findByEntry_IdOrderByLineNoAsc(original.getId())) {
            JournalLine nl = new JournalLine();
            nl.setAccount(ol.getAccount());
            nl.setDebit(ol.getCredit()); nl.setCredit(ol.getDebit());
            nl.setPropertyId(ol.getPropertyId()); nl.setUnitId(ol.getUnitId()); nl.setLeaseId(ol.getLeaseId());
            nl.setRenterId(ol.getRenterId()); nl.setChequeId(ol.getChequeId());
            nl.setNarration(ol.getNarration());
            rev.addLine(nl);
        }
        JournalEntry saved = entries.save(rev);
        lines.saveAll(saved.getLines());
        original.setStatus(JournalStatus.REVERSED);
        original.setReversedById(saved.getId());
        entries.save(original);
        return saved;
    }

    private void validateShape(PostingRequest r) {
        if (r.docType() == null) throw new BusinessRuleViolationException("docType is required");
        if (r.entryDate() == null) throw new BusinessRuleViolationException("entryDate is required");
        if (r.lines() == null || r.lines().size() < 2) throw new BusinessRuleViolationException("A journal entry needs at least two lines");
        for (Line l : r.lines()) {
            if (l.account() == null || l.side() == null || l.amount() == null) throw new BusinessRuleViolationException("Every line needs an account, a side and an amount");
        }
    }

    private Account resolveAccount(AccountRef ref, UUID propertyId) {
        if (ref instanceof ByRole br) return resolver.resolve(br.role(), propertyId);
        UUID id = ((ById) ref).accountId();
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("Account not found: " + id));
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
```

- [ ] **Step 5: Run the IT**

Run: `./gradlew test --tests '*PostingServiceIT'`
Expected: 9 PASS. If `postsBalancedEntry…` fails on `TCO-26/1` because another test already consumed the number, each test creates its own tenant so sequences are per tenant — check `TenantContextHolder` is set before `seedDefaultAccounts()`.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat(ledger): PostingService — validated, role-resolved, numbered, reversible journal postings

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Property account template, generate-missing, mappings API, property-create hook

**Files:**
- Create: `core/service/ledger/PropertyAccountService.java`
- Create: `api/PropertyAccountController.java`, `api/dto/ledger/RoleMappingDTO.java`, `api/dto/ledger/TemplateRowDTO.java`
- Modify: `core/service/PropertyService.java` (`createProperty` hook), `core/service/AccountService.java` (`seedDefaultAccounts` also seeds template + tenant defaults)
- Test: `core/service/ledger/PropertyAccountServiceIT.java`

**Interfaces:**
- Produces: `PropertyAccountService.generateMissing(UUID propertyId) : List<RoleMappingDTO>`, `getMappings(UUID propertyId) : List<RoleMappingDTO>` (one row per `AccountRole` where `isPropertyScoped()`, account nullable), `setMapping(UUID propertyId, AccountRole, UUID accountId)`, `clearMapping(UUID propertyId, AccountRole)`, `getTenantDefaults()`, `setTenantDefault(AccountRole, UUID accountId)`, `getTemplate() : List<TemplateRowDTO>`, `saveTemplate(List<TemplateRowDTO>)`, `seedDefaultTemplateAndDefaults()`.
- DTOs: `RoleMappingDTO(AccountRole role, UUID accountId, String accountCode, String accountName, boolean inherited)`; `TemplateRowDTO(AccountRole role, String namePattern, UUID parentAccountId, String parentCode, boolean enabled)`.
- Endpoints (all `@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')")`):
  - `GET /api/v1/properties/{id}/accounts` → `List<RoleMappingDTO>`
  - `POST /api/v1/properties/{id}/accounts/generate` → `List<RoleMappingDTO>`
  - `PUT /api/v1/properties/{id}/accounts/{role}` body `{accountId}` → `RoleMappingDTO`
  - `DELETE /api/v1/properties/{id}/accounts/{role}`
  - `GET /api/v1/finance/account-template`, `PUT /api/v1/finance/account-template` (full list)
  - `GET /api/v1/finance/default-accounts`, `PUT /api/v1/finance/default-accounts/{role}` body `{accountId}`

- [ ] **Step 1: Failing IT**

```java
package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.dto.ledger.RoleMappingDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class PropertyAccountServiceIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired PropertyAccountService service;
    @Autowired PropertyService properties;
    @Autowired AccountService accounts;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountResolver resolver;
    @Autowired LandlordOrgRepository orgRepo;

    @BeforeEach void tenant() {
        LandlordOrg org = new LandlordOrg(); org.setName("PA-" + UUID.randomUUID());
        TenantContextHolder.setTenantId(orgRepo.save(org).getId());
        accounts.seedDefaultAccounts();
    }
    @AfterEach void clear() { TenantContextHolder.clear(); }

    private Property newProperty(String name) {
        Property p = new Property(); p.setNameEn(name); p.setEmirate(Emirate.DUBAI);
        return properties.createProperty(p);
    }

    @Test
    void seedCreatesTemplateRowsAndTenantDefaults() {
        assertThat(service.getTemplate()).extracting(r -> r.role()).contains(
                AccountRole.RENT_RECEIVABLE, AccountRole.ADVANCE_RENT, AccountRole.RENTAL_INCOME, AccountRole.PDC_RECEIVABLE,
                AccountRole.BANK, AccountRole.SECURITY_DEPOSIT, AccountRole.ADMIN_FEE, AccountRole.RENT_PENALTY, AccountRole.CHEQUE_RETURN_PENALTY);
        assertThat(resolver.resolve(AccountRole.CASH, null).getCode()).isEqualTo("A-02-05-001");
        assertThat(resolver.resolve(AccountRole.OUTPUT_VAT, null).getCode()).isEqualTo("B-01-03-001");
        assertThat(resolver.resolve(AccountRole.INPUT_VAT, null).getCode()).isEqualTo("A-02-04-001");
        assertThat(resolver.resolve(AccountRole.ROUNDING_OFF, null).getCode()).isEqualTo("D-02-001");
        assertThat(resolver.resolve(AccountRole.OPENING_BALANCE_DIFFERENCE, null).getCode()).isEqualTo("F-02");
    }

    @Test
    void creatingAPropertyGeneratesItsAccountSetAndMappings() {
        Property p = newProperty("Tulip Oasis 7");
        List<RoleMappingDTO> m = service.getMappings(p.getId());
        RoleMappingDTO rr = m.stream().filter(x -> x.role() == AccountRole.RENT_RECEIVABLE).findFirst().orElseThrow();
        assertThat(rr.accountName()).isEqualTo("Rent Receivable - Tulip Oasis 7");
        assertThat(rr.inherited()).isFalse();
        Account leaf = accountRepo.findById(rr.accountId()).orElseThrow();
        assertThat(leaf.getParent().getCode()).isEqualTo("A-02-01");
        assertThat(leaf.getAccountType()).isEqualTo(AccountType.ASSET);
        assertThat(leaf.getPropertyId()).isEqualTo(p.getId());
        assertThat(resolver.resolve(AccountRole.ADVANCE_RENT, p.getId()).getName()).isEqualTo("Advance Rent - Tulip Oasis 7");
    }

    @Test
    void generateMissingIsIdempotentAndFillsOnlyGaps() {
        Property p = newProperty("Belle Vue");
        int before = accountRepo.findByProperty_Id(p.getId()).size();
        service.clearMapping(p.getId(), AccountRole.BANK);
        service.generateMissing(p.getId());
        // the existing "Emirates Islamic - Belle Vue" leaf is reused, not duplicated
        assertThat(accountRepo.findByProperty_Id(p.getId())).hasSize(before);
        assertThat(resolver.resolve(AccountRole.BANK, p.getId()).getName()).isEqualTo("Emirates Islamic - Belle Vue");
    }

    @Test
    void manualMappingToASharedAccountAndTypeCheck() {
        Property galah = newProperty("Galah Residence 2");
        Account generic = accounts.createLeaf("Rent Receivable", accounts.getAccountByCode("A-02-01"), null);
        service.setMapping(galah.getId(), AccountRole.RENT_RECEIVABLE, generic.getId());
        assertThat(resolver.resolve(AccountRole.RENT_RECEIVABLE, galah.getId()).getId()).isEqualTo(generic.getId());
        Account income = accounts.getAccountByCode("C-01-02-001");
        assertThatThrownBy(() -> service.setMapping(galah.getId(), AccountRole.RENT_RECEIVABLE, income.getId()))
                .hasMessageContaining("ASSET");
        Account group = accounts.getAccountByCode("A-02-01");
        assertThatThrownBy(() -> service.setMapping(galah.getId(), AccountRole.RENT_RECEIVABLE, group.getId()))
                .hasMessageContaining("group");
    }

    @Test
    void disabledTemplateRowIsSkipped() {
        var rows = service.getTemplate();
        service.saveTemplate(rows.stream().map(r -> r.role() == AccountRole.COOLING_CHARGES ? r.withEnabled(false) : r).toList());
        Property p = newProperty("OST-10");
        RoleMappingDTO cooling = service.getMappings(p.getId()).stream().filter(x -> x.role() == AccountRole.COOLING_CHARGES).findFirst().orElseThrow();
        assertThat(cooling.accountId()).isNull();
    }
}
```

- [ ] **Step 2: Run — compile failure.**

- [ ] **Step 3: DTOs**

```java
package com.datagami.rentaxis.api.dto.ledger;

import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import java.util.UUID;

/** inherited = resolved from the tenant default rather than a property mapping. */
public record RoleMappingDTO(AccountRole role, UUID accountId, String accountCode, String accountName, boolean inherited) {}
```

```java
package com.datagami.rentaxis.api.dto.ledger;

import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import java.util.UUID;

public record TemplateRowDTO(AccountRole role, String namePattern, UUID parentAccountId, String parentCode, boolean enabled) {
    public TemplateRowDTO withEnabled(boolean e) { return new TemplateRowDTO(role, namePattern, parentAccountId, parentCode, e); }
}
```

- [ ] **Step 4: Service**

```java
package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.dto.ledger.RoleMappingDTO;
import com.datagami.rentaxis.api.dto.ledger.TemplateRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Spec §5.3-5.4: template → per-property leaves + mappings; manual override; tenant defaults. */
@Slf4j
@Service
public class PropertyAccountService {

    private final PropertyAccountTemplateRowRepository templateRepo;
    private final PropertyAccountMappingRepository mappingRepo;
    private final TenantDefaultAccountMappingRepository defaultRepo;
    private final AccountRepository accountRepo;
    private final PropertyRepository propertyRepo;
    private final AccountService accountService;

    public PropertyAccountService(PropertyAccountTemplateRowRepository templateRepo, PropertyAccountMappingRepository mappingRepo,
                                  TenantDefaultAccountMappingRepository defaultRepo, AccountRepository accountRepo,
                                  PropertyRepository propertyRepo, AccountService accountService) {
        this.templateRepo = templateRepo; this.mappingRepo = mappingRepo; this.defaultRepo = defaultRepo;
        this.accountRepo = accountRepo; this.propertyRepo = propertyRepo; this.accountService = accountService;
    }

    // ---------- template ----------

    @Transactional(readOnly = true)
    public List<TemplateRowDTO> getTemplate() {
        return templateRepo.findAllByOrderByRoleAsc().stream().map(r -> new TemplateRowDTO(
                r.getRole(), r.getNamePattern(), r.getParentAccount().getId(), r.getParentAccount().getCode(), r.isEnabled())).toList();
    }

    @Transactional
    public List<TemplateRowDTO> saveTemplate(List<TemplateRowDTO> rows) {
        for (TemplateRowDTO dto : rows) {
            Account parent = accountRepo.findById(dto.parentAccountId()).orElseThrow(() -> new NotFoundException("Parent account not found"));
            if (!parent.isGroup()) throw new BusinessRuleViolationException("Template parent must be a group account: " + parent.getCode());
            if (dto.namePattern() == null || !dto.namePattern().contains("{property}"))
                throw new BusinessRuleViolationException("Name pattern must contain {property}: " + dto.role());
            PropertyAccountTemplateRow row = templateRepo.findByRole(dto.role()).orElseGet(PropertyAccountTemplateRow::new);
            row.setRole(dto.role()); row.setNamePattern(dto.namePattern()); row.setParentAccount(parent); row.setEnabled(dto.enabled());
            templateRepo.save(row);
        }
        return getTemplate();
    }

    /** Called from AccountService.seedDefaultAccounts after the CoA exists. Idempotent. */
    @Transactional
    public void seedDefaultTemplateAndDefaults() {
        if (templateRepo.count() == 0) {
            template(AccountRole.RENT_RECEIVABLE, "Rent Receivable - {property}", "A-02-01");
            template(AccountRole.ADVANCE_RENT, "Advance Rent - {property}", "B-01-01");
            template(AccountRole.RENTAL_INCOME, "Rental Income {property}", "C-01-01");
            template(AccountRole.PDC_RECEIVABLE, "PDC Receivable {property}", "A-02-03");
            template(AccountRole.BANK, "Emirates Islamic - {property}", "A-02-02");
            template(AccountRole.SECURITY_DEPOSIT, "Security Deposit {property}", "B-01-02");
            template(AccountRole.ADMIN_FEE, "Admin Fee - {property}", "C-01-01");
            template(AccountRole.PARKING_INCOME, "Additional Parking - {property}", "C-01-01");
            template(AccountRole.PARKING_DEPOSIT, "Parking Security Deposit {property}", "B-01-02");
            template(AccountRole.COOLING_CHARGES, "Cooling Charges - {property}", "C-01");
            template(AccountRole.MAINTENANCE_CHARGES, "Maintenance Charges - {property}", "C-01-02");
            template(AccountRole.RENT_PENALTY, "Rent Penalty - {property}", "C-01-02");
            template(AccountRole.CHEQUE_RETURN_PENALTY, "Cheque Return Penalty - {property}", "C-01-02");
        }
        defaultIfMissing(AccountRole.CASH, "A-02-05-001");
        defaultIfMissing(AccountRole.OUTPUT_VAT, "B-01-03-001");
        defaultIfMissing(AccountRole.INPUT_VAT, "A-02-04-001");
        defaultIfMissing(AccountRole.ROUNDING_OFF, "D-02-001");
        defaultIfMissing(AccountRole.DISCOUNT_ALLOWED, "D-02-002");
        defaultIfMissing(AccountRole.FORFEITED_INCOME, "C-01-02-001");
        defaultIfMissing(AccountRole.OPENING_BALANCE_DIFFERENCE, "F-02");
    }

    private void template(AccountRole role, String pattern, String parentCode) {
        PropertyAccountTemplateRow r = new PropertyAccountTemplateRow();
        r.setRole(role); r.setNamePattern(pattern); r.setParentAccount(accountService.getAccountByCode(parentCode)); r.setEnabled(true);
        templateRepo.save(r);
    }

    private void defaultIfMissing(AccountRole role, String code) {
        if (defaultRepo.findByRole(role).isPresent()) return;
        TenantDefaultAccountMapping m = new TenantDefaultAccountMapping();
        m.setRole(role); m.setAccount(accountService.getAccountByCode(code));
        defaultRepo.save(m);
    }

    // ---------- per-property ----------

    /** Creates a leaf per enabled template row that has no mapping yet, reusing a same-named leaf under the same parent. */
    @Transactional
    public List<RoleMappingDTO> generateMissing(UUID propertyId) {
        Property property = propertyRepo.findById(propertyId).orElseThrow(() -> new NotFoundException("Property not found"));
        List<PropertyAccountTemplateRow> rows = templateRepo.findByEnabledTrue();
        if (rows.isEmpty()) {
            log.warn("No account template for tenant; skipping account generation for property {}", propertyId);
            return getMappings(propertyId);
        }
        for (PropertyAccountTemplateRow row : rows) {
            if (mappingRepo.findByPropertyIdAndRole(propertyId, row.getRole()).isPresent()) continue;
            String name = row.getNamePattern().replace("{property}", property.getNameEn());
            Account leaf = accountRepo.findByNameAndParent_Id(name, row.getParentAccount().getId())
                    .orElseGet(() -> accountService.createLeaf(name, row.getParentAccount(), propertyId));
            if (leaf.getProperty() == null) { leaf.setProperty(property); accountRepo.save(leaf); }
            PropertyAccountMapping m = new PropertyAccountMapping();
            m.setPropertyId(propertyId); m.setRole(row.getRole()); m.setAccount(leaf);
            mappingRepo.save(m);
        }
        return getMappings(propertyId);
    }

    @Transactional(readOnly = true)
    public List<RoleMappingDTO> getMappings(UUID propertyId) {
        Map<AccountRole, PropertyAccountMapping> own = new EnumMap<>(AccountRole.class);
        mappingRepo.findByPropertyId(propertyId).forEach(m -> own.put(m.getRole(), m));
        Map<AccountRole, TenantDefaultAccountMapping> defaults = new EnumMap<>(AccountRole.class);
        defaultRepo.findAllByOrderByRoleAsc().forEach(m -> defaults.put(m.getRole(), m));
        List<RoleMappingDTO> out = new ArrayList<>();
        for (AccountRole role : AccountRole.values()) {
            if (!role.isPropertyScoped()) continue;
            PropertyAccountMapping m = own.get(role);
            if (m != null) { out.add(dto(role, m.getAccount(), false)); continue; }
            TenantDefaultAccountMapping d = defaults.get(role);
            out.add(d != null ? dto(role, d.getAccount(), true) : new RoleMappingDTO(role, null, null, null, false));
        }
        return out;
    }

    @Transactional
    public RoleMappingDTO setMapping(UUID propertyId, AccountRole role, UUID accountId) {
        propertyRepo.findById(propertyId).orElseThrow(() -> new NotFoundException("Property not found"));
        Account account = accountRepo.findById(accountId).orElseThrow(() -> new NotFoundException("Account not found"));
        if (account.isGroup()) throw new BusinessRuleViolationException("Cannot map a group account");
        templateRepo.findByRole(role).ifPresent(row -> {
            if (row.getParentAccount().getAccountType() != account.getAccountType()) {
                throw new BusinessRuleViolationException(role + " expects an " + row.getParentAccount().getAccountType() + " account, got " + account.getAccountType());
            }
        });
        PropertyAccountMapping m = mappingRepo.findByPropertyIdAndRole(propertyId, role).orElseGet(PropertyAccountMapping::new);
        m.setPropertyId(propertyId); m.setRole(role); m.setAccount(account);
        mappingRepo.save(m);
        return dto(role, account, false);
    }

    @Transactional
    public void clearMapping(UUID propertyId, AccountRole role) {
        mappingRepo.findByPropertyIdAndRole(propertyId, role).ifPresent(mappingRepo::delete);
    }

    // ---------- tenant defaults ----------

    @Transactional(readOnly = true)
    public List<RoleMappingDTO> getTenantDefaults() {
        Map<AccountRole, TenantDefaultAccountMapping> defaults = new EnumMap<>(AccountRole.class);
        defaultRepo.findAllByOrderByRoleAsc().forEach(m -> defaults.put(m.getRole(), m));
        List<RoleMappingDTO> out = new ArrayList<>();
        for (AccountRole role : AccountRole.values()) {
            TenantDefaultAccountMapping d = defaults.get(role);
            out.add(d != null ? dto(role, d.getAccount(), false) : new RoleMappingDTO(role, null, null, null, false));
        }
        return out;
    }

    @Transactional
    public RoleMappingDTO setTenantDefault(AccountRole role, UUID accountId) {
        Account account = accountRepo.findById(accountId).orElseThrow(() -> new NotFoundException("Account not found"));
        if (account.isGroup()) throw new BusinessRuleViolationException("Cannot map a group account");
        TenantDefaultAccountMapping m = defaultRepo.findByRole(role).orElseGet(TenantDefaultAccountMapping::new);
        m.setRole(role); m.setAccount(account);
        defaultRepo.save(m);
        return dto(role, account, false);
    }

    private static RoleMappingDTO dto(AccountRole role, Account a, boolean inherited) {
        return new RoleMappingDTO(role, a.getId(), a.getCode(), a.getName(), inherited);
    }
}
```

`AccountService.seedDefaultAccounts()` must call `seedDefaultTemplateAndDefaults()` after `saveAll`. To avoid a circular constructor dependency (`PropertyAccountService` → `AccountService`), inject `PropertyAccountService` into `AccountService` with `@Lazy`, or better: move the call to the controller/seed entry point — `AccountController.seedDefaultAccounts()` calls `service.seedDefaultAccounts()` then `propertyAccountService.seedDefaultTemplateAndDefaults()`. Do the latter and also call both from `PostingServiceIT`/`PropertyAccountServiceIT` setup (replace the bare `accounts.seedDefaultAccounts()` in those tests with a two-line seed: `accounts.seedDefaultAccounts(); propertyAccounts.seedDefaultTemplateAndDefaults();`).

- [ ] **Step 5: Hook `PropertyService.createProperty`**

After `Property saved = repository.save(property);` inside the `try`, add `propertyAccountService.generateMissing(saved.getId());` and return `saved`. Inject `PropertyAccountService` (constructor). `generateMissing` already no-ops with a warning when the tenant has no template, so existing tests that create properties without a CoA still pass.

- [ ] **Step 6: Controller**

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ledger.RoleMappingDTO;
import com.datagami.rentaxis.api.dto.ledger.TemplateRowDTO;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
public class PropertyAccountController {

    private final PropertyAccountService service;
    public PropertyAccountController(PropertyAccountService service) { this.service = service; }

    public record AccountIdBody(UUID accountId) {}

    @GetMapping("/api/v1/properties/{id}/accounts")
    public ResponseEntity<List<RoleMappingDTO>> mappings(@PathVariable UUID id) { return ResponseEntity.ok(service.getMappings(id)); }

    @PostMapping("/api/v1/properties/{id}/accounts/generate")
    public ResponseEntity<List<RoleMappingDTO>> generate(@PathVariable UUID id) { return ResponseEntity.ok(service.generateMissing(id)); }

    @PutMapping("/api/v1/properties/{id}/accounts/{role}")
    public ResponseEntity<RoleMappingDTO> set(@PathVariable UUID id, @PathVariable AccountRole role, @RequestBody AccountIdBody body) {
        return ResponseEntity.ok(service.setMapping(id, role, body.accountId()));
    }

    @DeleteMapping("/api/v1/properties/{id}/accounts/{role}")
    public ResponseEntity<Void> clear(@PathVariable UUID id, @PathVariable AccountRole role) { service.clearMapping(id, role); return ResponseEntity.noContent().build(); }

    @GetMapping("/api/v1/finance/account-template")
    public ResponseEntity<List<TemplateRowDTO>> template() { return ResponseEntity.ok(service.getTemplate()); }

    @PutMapping("/api/v1/finance/account-template")
    public ResponseEntity<List<TemplateRowDTO>> saveTemplate(@RequestBody List<TemplateRowDTO> rows) { return ResponseEntity.ok(service.saveTemplate(rows)); }

    @GetMapping("/api/v1/finance/default-accounts")
    public ResponseEntity<List<RoleMappingDTO>> defaults() { return ResponseEntity.ok(service.getTenantDefaults()); }

    @PutMapping("/api/v1/finance/default-accounts/{role}")
    public ResponseEntity<RoleMappingDTO> setDefault(@PathVariable AccountRole role, @RequestBody AccountIdBody body) {
        return ResponseEntity.ok(service.setTenantDefault(role, body.accountId()));
    }

    @GetMapping("/api/v1/finance/account-roles")
    public ResponseEntity<List<Map<String, Object>>> roles() {
        return ResponseEntity.ok(java.util.Arrays.stream(AccountRole.values())
                .map(r -> Map.<String, Object>of("role", r.name(), "propertyScoped", r.isPropertyScoped())).toList());
    }
}
```

- [ ] **Step 7: Run, commit**

Run: `./gradlew test --tests '*PropertyAccountServiceIT' --tests '*PostingServiceIT' --tests '*PropertyService*'`
Expected: PASS.

```bash
git add backend/src
git commit -m "feat(ledger): property account template, generate-missing on property create, mapping API

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Silent ledger accounts for vendors and bank accounts

**Files:**
- Modify: `core/service/VendorService.java`, `core/service/BankAccountService.java`
- Test: `core/service/ledger/SilentAccountsIT.java`

**Interfaces:**
- Produces: on `VendorService.createVendor` → `vendor.payableAccount` = new leaf under `B-01-04` named `nameEn`; on rename → leaf renamed; on deactivate → leaf `isActive=false`. On `BankAccountService.create` with `coaAccount == null` → property's `BANK` mapping if `property != null`, else new leaf `"<bankName> - <accountNumber last 4>"` under `A-02-02`.
- Consumes: `AccountService.createLeaf`, `AccountService.getAccountByCode`, `AccountResolver`.

- [ ] **Step 1: Failing IT**

```java
package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.core.service.*;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class SilentAccountsIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired VendorService vendors;
    @Autowired BankAccountService bankAccounts;
    @Autowired PropertyService properties;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired AccountResolver resolver;
    @Autowired LandlordOrgRepository orgRepo;

    @BeforeEach void tenant() {
        LandlordOrg org = new LandlordOrg(); org.setName("SA-" + UUID.randomUUID());
        TenantContextHolder.setTenantId(orgRepo.save(org).getId());
        accounts.seedDefaultAccounts(); propertyAccounts.seedDefaultTemplateAndDefaults();
    }
    @AfterEach void clear() { TenantContextHolder.clear(); }

    @Test
    void vendorGetsALeafUnderVendorsGroupNamedAfterIt() {
        Vendor v = new Vendor(); v.setNameEn("HAPPY LIVING PEST CONTROL SERVICES");
        v = vendors.createVendor(v);
        assertThat(v.getPayableAccount()).isNotNull();
        assertThat(v.getPayableAccount().getName()).isEqualTo("HAPPY LIVING PEST CONTROL SERVICES");
        assertThat(v.getPayableAccount().getParent().getCode()).isEqualTo("B-01-04");
        assertThat(v.getPayableAccount().isGroup()).isFalse();
    }

    @Test
    void renamingAndDeactivatingAVendorFollowsThroughToItsLeaf() {
        Vendor v = new Vendor(); v.setNameEn("Old Name"); v = vendors.createVendor(v);
        Vendor upd = new Vendor(); upd.setNameEn("New Name"); upd.setActive(false);
        v = vendors.updateVendor(v.getId(), upd);
        assertThat(v.getPayableAccount().getName()).isEqualTo("New Name");
        assertThat(v.getPayableAccount().isActive()).isFalse();
    }

    @Test
    void bankAccountOnAPropertyDefaultsToThePropertyBankLeaf() {
        Property p = new Property(); p.setNameEn("Tara 2"); p.setEmirate(Emirate.DUBAI); p = properties.createProperty(p);
        BankAccount b = new BankAccount(); b.setBankName("Emirates Islamic"); b.setAccountNumber("1234567890"); b.setProperty(p);
        b = bankAccounts.create(b);
        assertThat(b.getCoaAccount().getId()).isEqualTo(resolver.resolve(AccountRole.BANK, p.getId()).getId());
    }

    @Test
    void bankAccountWithoutPropertyGetsItsOwnLeaf() {
        BankAccount b = new BankAccount(); b.setBankName("ENBD"); b.setAccountNumber("9988776655");
        b = bankAccounts.create(b);
        assertThat(b.getCoaAccount().getName()).isEqualTo("ENBD - 6655");
        assertThat(b.getCoaAccount().getParent().getCode()).isEqualTo("A-02-02");
    }
}
```

Check the actual method names on `VendorService` (`createVendor`/`updateVendor`) and `BankAccountService` (`create`/`createBankAccount`) and use those; the test names above are the plan's assumption — adjust the test, not the service's public API.

- [ ] **Step 2: Implement**

In `VendorService` create: after validation and before `repository.save(vendor)`:

```java
        if (vendor.getPayableAccount() == null) {
            Account vendorsGroup = accountService.getAccountByCode("B-01-04");
            vendor.setPayableAccount(accountService.createLeaf(vendor.getNameEn(), vendorsGroup, null));
        }
```

In update, after copying fields:

```java
        Account leaf = existing.getPayableAccount();
        if (leaf != null && !leaf.isSystem()) {
            leaf.setName(existing.getNameEn()); leaf.setNameEn(existing.getNameEn()); leaf.setNameAr(existing.getNameAr());
            leaf.setActive(existing.isActive());
            accountRepository.save(leaf);
        }
```

If the tenant has no `B-01-04` (CoA not seeded), `getAccountByCode` throws `NotFoundException`; catch it, log a warning, and save the vendor without a leaf — a vendor master must never fail because finance isn't set up yet. The purchase voucher (Plan 4) refuses vendors with no `payableAccount` and offers "create ledger account".

In `BankAccountService` create: if `coaAccount == null`:

```java
            if (bankAccount.getProperty() != null) {
                try { bankAccount.setCoaAccount(resolver.resolve(AccountRole.BANK, bankAccount.getProperty().getId())); }
                catch (UnmappedAccountRoleException e) { /* fall through to own leaf */ }
            }
            if (bankAccount.getCoaAccount() == null) {
                String last4 = bankAccount.getAccountNumber().length() > 4
                        ? bankAccount.getAccountNumber().substring(bankAccount.getAccountNumber().length() - 4) : bankAccount.getAccountNumber();
                Account bankGroup = accountService.getAccountByCode("A-02-02");
                bankAccount.setCoaAccount(accountService.createLeaf(bankAccount.getBankName() + " - " + last4, bankGroup, null));
            }
```

with the same `NotFoundException` guard.

- [ ] **Step 3: Run, commit**

Run: `./gradlew test --tests '*SilentAccountsIT' --tests '*VendorService*' --tests '*BankAccount*'` → PASS.

```bash
git add backend/src
git commit -m "feat(ledger): vendors and bank accounts get their ledger leaf silently

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: Ledger queries — account ledger, general ledger, renter ledger, vendor ledger, trial balance

**Files:**
- Modify: `domain/repository/JournalLineRepository.java` (aggregation queries)
- Create: `api/dto/ledger/LedgerRowDTO.java`, `AccountLedgerDTO.java`, `TrialBalanceRowDTO.java`
- Create: `core/service/ledger/LedgerQueryService.java`, `api/LedgerController.java`
- Test: `core/service/ledger/LedgerQueryServiceIT.java`

**Interfaces:**
- Produces:
  ```java
  record LedgerFilter(LocalDate from, LocalDate to, UUID propertyId, UUID unitId, UUID leaseId, UUID renterId)
  record LedgerRowDTO(UUID entryId, String entryNumber, LocalDate entryDate, String docType, String particular, String narration,
                      BigDecimal debit, BigDecimal credit, BigDecimal balance, UUID propertyId, UUID unitId, UUID leaseId, UUID renterId, UUID chequeId)
  record AccountLedgerDTO(UUID accountId, String accountCode, String accountName, String accountType, BigDecimal openingBalance,
                          List<LedgerRowDTO> rows, BigDecimal totalDebit, BigDecimal totalCredit, BigDecimal closingBalance, boolean truncated)
  record TrialBalanceRowDTO(UUID accountId, String code, String name, String accountType, UUID parentId, UUID propertyId,
                            BigDecimal debit, BigDecimal credit, BigDecimal balance)
  AccountLedgerDTO   LedgerQueryService.accountLedger(UUID accountId, LedgerFilter f)
  List<AccountLedgerDTO> LedgerQueryService.generalLedger(List<UUID> accountIds, LedgerFilter f)   // accountIds empty => every leaf with activity in range
  List<AccountLedgerDTO> LedgerQueryService.renterLedger(UUID renterId, LocalDate from, LocalDate to)
  AccountLedgerDTO   LedgerQueryService.vendorLedger(UUID vendorId, LocalDate from, LocalDate to)
  List<TrialBalanceRowDTO> LedgerQueryService.trialBalance(LocalDate asOf, UUID propertyId)
  ```
  Balances are signed: debit-positive. `balance` on a row is the running balance after that row. Rows capped at `MAX_ROWS = 5000` (`truncated = true` beyond).
- Endpoints (`@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')")`):
  - `GET /api/v1/finance/ledger?accountIds=a,b&from&to&propertyId&unitId&leaseId&renterId` → `List<AccountLedgerDTO>`
  - `GET /api/v1/finance/ledger/account/{accountId}?from&to&…` → `AccountLedgerDTO`
  - `GET /api/v1/finance/ledger/renter/{renterId}?from&to` → `List<AccountLedgerDTO>`
  - `GET /api/v1/finance/ledger/vendor/{vendorId}?from&to` → `AccountLedgerDTO`
  - `GET /api/v1/finance/trial-balance?asOf&propertyId` → `List<TrialBalanceRowDTO>`

- [ ] **Step 1: Failing IT**

```java
package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.core.service.ledger.PostingRequest.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class LedgerQueryServiceIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired LedgerQueryService ledger;
    @Autowired PostingService posting;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired PropertyService properties;
    @Autowired AccountResolver resolver;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired RenterRepository renterRepo;

    UUID propertyId, renterId, leaseId;

    @BeforeEach void setUp() {
        LandlordOrg org = new LandlordOrg(); org.setName("LQ-" + UUID.randomUUID());
        TenantContextHolder.setTenantId(orgRepo.save(org).getId());
        accounts.seedDefaultAccounts(); propertyAccounts.seedDefaultTemplateAndDefaults();
        Property p = new Property(); p.setNameEn("L'Olivier"); p.setEmirate(Emirate.DUBAI);
        propertyId = properties.createProperty(p).getId();
        Renter r = new Renter(); r.setFullName("Prabhjot Singh");   // adjust to Renter's actual required fields
        renterId = renterRepo.save(r).getId();
        leaseId = UUID.randomUUID();
        Dimensions dims = new Dimensions(propertyId, null, leaseId, renterId, null);
        // TCO 11-09-2026: Dr RR 64,500 / Cr Advance 61,000, SD 3,000, Admin 500
        posting.post(new PostingRequest(JournalDocType.TCO, LocalDate.of(2026, 9, 11), "Contract", dims, JournalSourceType.LEASE, leaseId, null, List.of(
                dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("64500")),
                cr(AccountRole.ADVANCE_RENT, new BigDecimal("61000")),
                cr(AccountRole.SECURITY_DEPOSIT, new BigDecimal("3000")),
                cr(AccountRole.ADMIN_FEE, new BigDecimal("500")))));
        // PDR 11-09-2026: Dr PDC 13,700 / Cr RR 13,700
        posting.post(new PostingRequest(JournalDocType.PDR, LocalDate.of(2026, 9, 11), "PDC 1", dims, JournalSourceType.CHEQUE, UUID.randomUUID(), null, List.of(
                dr(AccountRole.PDC_RECEIVABLE, new BigDecimal("13700")), cr(AccountRole.RENT_RECEIVABLE, new BigDecimal("13700")))));
        // CRT 15-09-2026: Dr Bank / Cr PDC 13,700
        posting.post(new PostingRequest(JournalDocType.CRT, LocalDate.of(2026, 9, 15), "Cleared", dims, JournalSourceType.CHEQUE, UUID.randomUUID(), null, List.of(
                dr(AccountRole.BANK, new BigDecimal("13700")), cr(AccountRole.PDC_RECEIVABLE, new BigDecimal("13700")))));
    }
    @AfterEach void clear() { TenantContextHolder.clear(); }

    @Test
    void accountLedgerHasRunningBalanceAndCounterAccountParticular() {
        Account rr = resolver.resolve(AccountRole.RENT_RECEIVABLE, propertyId);
        AccountLedgerDTO l = ledger.accountLedger(rr.getId(), new LedgerQueryService.LedgerFilter(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, null, null, null));
        assertThat(l.openingBalance()).isEqualByComparingTo("0");
        assertThat(l.rows()).hasSize(2);
        assertThat(l.rows().get(0).debit()).isEqualByComparingTo("64500");
        assertThat(l.rows().get(0).particular()).contains("Advance Rent - L'Olivier").contains("Security Deposit L'Olivier").contains("Admin Fee - L'Olivier");
        assertThat(l.rows().get(0).balance()).isEqualByComparingTo("64500");
        assertThat(l.rows().get(1).credit()).isEqualByComparingTo("13700");
        assertThat(l.rows().get(1).particular()).isEqualTo("PDC Receivable L'Olivier");
        assertThat(l.rows().get(1).balance()).isEqualByComparingTo("50800");
        assertThat(l.closingBalance()).isEqualByComparingTo("50800");
        assertThat(l.totalDebit()).isEqualByComparingTo("64500");
        assertThat(l.totalCredit()).isEqualByComparingTo("13700");
    }

    @Test
    void openingBalanceCarriesActivityBeforeTheRange() {
        Account pdc = resolver.resolve(AccountRole.PDC_RECEIVABLE, propertyId);
        AccountLedgerDTO l = ledger.accountLedger(pdc.getId(), new LedgerQueryService.LedgerFilter(LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 30), null, null, null, null));
        assertThat(l.openingBalance()).isEqualByComparingTo("13700");
        assertThat(l.rows()).hasSize(1);
        assertThat(l.closingBalance()).isEqualByComparingTo("0");
    }

    @Test
    void renterLedgerGroupsByAccountAndOnlyShowsThatRenter() {
        List<AccountLedgerDTO> l = ledger.renterLedger(renterId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(l).extracting(AccountLedgerDTO::accountName).containsExactlyInAnyOrder(
                "Rent Receivable - L'Olivier", "Advance Rent - L'Olivier", "Security Deposit L'Olivier", "Admin Fee - L'Olivier",
                "PDC Receivable L'Olivier", "Emirates Islamic - L'Olivier");
        assertThat(ledger.renterLedger(UUID.randomUUID(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))).isEmpty();
    }

    @Test
    void generalLedgerWithNoAccountIdsReturnsEveryLeafWithActivityInRange() {
        List<AccountLedgerDTO> gl = ledger.generalLedger(List.of(), new LedgerQueryService.LedgerFilter(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 15), null, null, null, null));
        assertThat(gl).extracting(AccountLedgerDTO::accountName).containsExactlyInAnyOrder("Emirates Islamic - L'Olivier", "PDC Receivable L'Olivier");
    }

    @Test
    void trialBalanceBalancesAndFiltersByProperty() {
        List<TrialBalanceRowDTO> tb = ledger.trialBalance(LocalDate.of(2026, 9, 30), null);
        BigDecimal dr = tb.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cr = tb.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(dr).isEqualByComparingTo(cr);
        TrialBalanceRowDTO adv = tb.stream().filter(r -> r.name().equals("Advance Rent - L'Olivier")).findFirst().orElseThrow();
        assertThat(adv.balance()).isEqualByComparingTo("-61000"); // credit balance, signed debit-positive
        assertThat(ledger.trialBalance(LocalDate.of(2026, 9, 10), null)).isEmpty();
        assertThat(ledger.trialBalance(LocalDate.of(2026, 9, 30), UUID.randomUUID())).isEmpty();
    }
}
```

Set whatever fields `Renter` requires (`fullName`/`nameEn`/`phone` — read `domain/entity/Renter.java`).

- [ ] **Step 2: Run — compile failure.**

- [ ] **Step 3: Repository queries** (add to `JournalLineRepository`)

```java
    interface LineRow {
        UUID getEntryId(); String getEntryNumber(); LocalDate getEntryDate(); String getDocType(); String getEntryNarration();
        String getLineNarration(); BigDecimal getDebit(); BigDecimal getCredit();
        UUID getPropertyId(); UUID getUnitId(); UUID getLeaseId(); UUID getRenterId(); UUID getChequeId(); int getLineNo();
    }

    interface CounterRow { UUID getEntryId(); String getNames(); }

    interface BalanceRow { UUID getAccountId(); BigDecimal getDebit(); BigDecimal getCredit(); }

    @Query(value = """
        select e.id as entryId, e.entry_number as entryNumber, e.entry_date as entryDate, e.doc_type as docType,
               e.narration as entryNarration, l.narration as lineNarration, l.debit as debit, l.credit as credit,
               l.property_id as propertyId, l.unit_id as unitId, l.lease_id as leaseId, l.renter_id as renterId, l.cheque_id as chequeId, l.line_no as lineNo
        from journal_lines l join journal_entries e on e.id = l.journal_entry_id
        where l.tenant_id = :tenantId and l.account_id = :accountId
          and e.entry_date between :from and :to
          and (cast(:propertyId as uuid) is null or l.property_id = :propertyId)
          and (cast(:unitId as uuid) is null or l.unit_id = :unitId)
          and (cast(:leaseId as uuid) is null or l.lease_id = :leaseId)
          and (cast(:renterId as uuid) is null or l.renter_id = :renterId)
        order by e.entry_date, e.created_at, l.line_no
        limit :limit
        """, nativeQuery = true)
    List<LineRow> ledgerRows(UUID tenantId, UUID accountId, LocalDate from, LocalDate to,
                             UUID propertyId, UUID unitId, UUID leaseId, UUID renterId, int limit);

    @Query(value = """
        select coalesce(sum(l.debit),0) - coalesce(sum(l.credit),0)
        from journal_lines l join journal_entries e on e.id = l.journal_entry_id
        where l.tenant_id = :tenantId and l.account_id = :accountId and e.entry_date < :before
          and (cast(:propertyId as uuid) is null or l.property_id = :propertyId)
          and (cast(:unitId as uuid) is null or l.unit_id = :unitId)
          and (cast(:leaseId as uuid) is null or l.lease_id = :leaseId)
          and (cast(:renterId as uuid) is null or l.renter_id = :renterId)
        """, nativeQuery = true)
    BigDecimal balanceBefore(UUID tenantId, UUID accountId, LocalDate before, UUID propertyId, UUID unitId, UUID leaseId, UUID renterId);

    /** Names of the OTHER accounts on each entry — the "Particular" column. */
    @Query(value = """
        select l.journal_entry_id as entryId, string_agg(distinct a.name, ' / ' order by a.name) as names
        from journal_lines l join accounts a on a.id = l.account_id
        where l.journal_entry_id in (:entryIds) and l.account_id <> :accountId
        group by l.journal_entry_id
        """, nativeQuery = true)
    List<CounterRow> counterAccounts(Collection<UUID> entryIds, UUID accountId);

    @Query(value = """
        select distinct l.account_id as accountId
        from journal_lines l join journal_entries e on e.id = l.journal_entry_id
        where l.tenant_id = :tenantId and e.entry_date between :from and :to
          and (cast(:propertyId as uuid) is null or l.property_id = :propertyId)
          and (cast(:unitId as uuid) is null or l.unit_id = :unitId)
          and (cast(:leaseId as uuid) is null or l.lease_id = :leaseId)
          and (cast(:renterId as uuid) is null or l.renter_id = :renterId)
        """, nativeQuery = true)
    List<UUID> activeAccountIds(UUID tenantId, LocalDate from, LocalDate to, UUID propertyId, UUID unitId, UUID leaseId, UUID renterId);

    @Query(value = """
        select l.account_id as accountId, coalesce(sum(l.debit),0) as debit, coalesce(sum(l.credit),0) as credit
        from journal_lines l join journal_entries e on e.id = l.journal_entry_id
        where l.tenant_id = :tenantId and e.entry_date <= :asOf
          and (cast(:propertyId as uuid) is null or l.property_id = :propertyId)
        group by l.account_id
        having coalesce(sum(l.debit),0) <> 0 or coalesce(sum(l.credit),0) <> 0
        """, nativeQuery = true)
    List<BalanceRow> balancesAsOf(UUID tenantId, LocalDate asOf, UUID propertyId);
```

Native queries bypass the Hibernate tenant filter, which is why every one takes `tenantId` explicitly. The `cast(:x as uuid) is null` form is what Postgres needs for nullable uuid parameters.

- [ ] **Step 4: DTOs + service + controller**

DTOs are the records shown under Interfaces, in `api/dto/ledger/`.

`core/service/ledger/LedgerQueryService.java`:

```java
package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.dto.ledger.*;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Vendor;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository.*;
import com.datagami.rentaxis.domain.repository.VendorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class LedgerQueryService {

    public static final int MAX_ROWS = 5000;

    public record LedgerFilter(LocalDate from, LocalDate to, UUID propertyId, UUID unitId, UUID leaseId, UUID renterId) {
        LedgerFilter withRenter(UUID r) { return new LedgerFilter(from, to, propertyId, unitId, leaseId, r); }
        LedgerFilter normalised() {
            LocalDate f = from == null ? LocalDate.of(2000, 1, 1) : from;
            LocalDate t = to == null ? LocalDate.of(2099, 12, 31) : to;
            if (t.isBefore(f)) throw new BusinessRuleViolationException("'to' must not be before 'from'");
            return new LedgerFilter(f, t, propertyId, unitId, leaseId, renterId);
        }
    }

    private final JournalLineRepository lines;
    private final AccountRepository accounts;
    private final VendorRepository vendors;

    public LedgerQueryService(JournalLineRepository lines, AccountRepository accounts, VendorRepository vendors) {
        this.lines = lines; this.accounts = accounts; this.vendors = vendors;
    }

    public AccountLedgerDTO accountLedger(UUID accountId, LedgerFilter filter) {
        LedgerFilter f = filter.normalised();
        UUID tenantId = TenantContextHolder.getTenantId();
        Account account = accounts.findById(accountId).orElseThrow(() -> new NotFoundException("Account not found"));
        BigDecimal opening = lines.balanceBefore(tenantId, accountId, f.from(), f.propertyId(), f.unitId(), f.leaseId(), f.renterId());
        List<LineRow> raw = lines.ledgerRows(tenantId, accountId, f.from(), f.to(), f.propertyId(), f.unitId(), f.leaseId(), f.renterId(), MAX_ROWS + 1);
        boolean truncated = raw.size() > MAX_ROWS;
        if (truncated) raw = raw.subList(0, MAX_ROWS);
        Map<UUID, String> particulars = raw.isEmpty() ? Map.of() : lines.counterAccounts(
                raw.stream().map(LineRow::getEntryId).collect(Collectors.toSet()), accountId)
                .stream().collect(Collectors.toMap(CounterRow::getEntryId, CounterRow::getNames));
        BigDecimal running = opening, totalDr = BigDecimal.ZERO, totalCr = BigDecimal.ZERO;
        List<LedgerRowDTO> rows = new ArrayList<>(raw.size());
        for (LineRow r : raw) {
            running = running.add(r.getDebit()).subtract(r.getCredit());
            totalDr = totalDr.add(r.getDebit()); totalCr = totalCr.add(r.getCredit());
            rows.add(new LedgerRowDTO(r.getEntryId(), r.getEntryNumber(), r.getEntryDate(), r.getDocType(),
                    particulars.getOrDefault(r.getEntryId(), ""), r.getLineNarration() != null ? r.getLineNarration() : r.getEntryNarration(),
                    r.getDebit(), r.getCredit(), running, r.getPropertyId(), r.getUnitId(), r.getLeaseId(), r.getRenterId(), r.getChequeId()));
        }
        return new AccountLedgerDTO(account.getId(), account.getCode(), account.getName(), account.getAccountType().name(),
                opening, rows, totalDr, totalCr, running, truncated);
    }

    public List<AccountLedgerDTO> generalLedger(List<UUID> accountIds, LedgerFilter filter) {
        LedgerFilter f = filter.normalised();
        List<UUID> ids = (accountIds == null || accountIds.isEmpty())
                ? lines.activeAccountIds(TenantContextHolder.getTenantId(), f.from(), f.to(), f.propertyId(), f.unitId(), f.leaseId(), f.renterId())
                : accountIds;
        return ids.stream().map(id -> accountLedger(id, f))
                .sorted(Comparator.comparing(AccountLedgerDTO::accountCode)).toList();
    }

    public List<AccountLedgerDTO> renterLedger(UUID renterId, LocalDate from, LocalDate to) {
        LedgerFilter f = new LedgerFilter(from, to, null, null, null, renterId).normalised();
        List<UUID> ids = lines.activeAccountIds(TenantContextHolder.getTenantId(), f.from(), f.to(), null, null, null, renterId);
        return ids.stream().map(id -> accountLedger(id, f)).sorted(Comparator.comparing(AccountLedgerDTO::accountCode)).toList();
    }

    public AccountLedgerDTO vendorLedger(UUID vendorId, LocalDate from, LocalDate to) {
        Vendor v = vendors.findById(vendorId).orElseThrow(() -> new NotFoundException("Vendor not found"));
        if (v.getPayableAccount() == null) throw new BusinessRuleViolationException("Vendor has no ledger account yet");
        return accountLedger(v.getPayableAccount().getId(), new LedgerFilter(from, to, null, null, null, null));
    }

    public List<TrialBalanceRowDTO> trialBalance(LocalDate asOf, UUID propertyId) {
        LocalDate d = asOf == null ? LocalDate.now() : asOf;
        List<BalanceRow> balances = lines.balancesAsOf(TenantContextHolder.getTenantId(), d, propertyId);
        if (balances.isEmpty()) return List.of();
        Map<UUID, Account> byId = accounts.findAllById(balances.stream().map(BalanceRow::getAccountId).toList())
                .stream().collect(Collectors.toMap(Account::getId, a -> a));
        return balances.stream().map(b -> {
            Account a = byId.get(b.getAccountId());
            return new TrialBalanceRowDTO(a.getId(), a.getCode(), a.getName(), a.getAccountType().name(), a.getParentId(), a.getPropertyId(),
                    b.getDebit(), b.getCredit(), b.getDebit().subtract(b.getCredit()));
        }).sorted(Comparator.comparing(TrialBalanceRowDTO::code)).toList();
    }
}
```

`api/LedgerController.java`:

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService.LedgerFilter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
public class LedgerController {

    private final LedgerQueryService service;
    public LedgerController(LedgerQueryService service) { this.service = service; }

    @GetMapping("/ledger")
    public ResponseEntity<List<AccountLedgerDTO>> generalLedger(
            @RequestParam(required = false) List<UUID> accountIds,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID propertyId, @RequestParam(required = false) UUID unitId,
            @RequestParam(required = false) UUID leaseId, @RequestParam(required = false) UUID renterId) {
        return ResponseEntity.ok(service.generalLedger(accountIds, new LedgerFilter(from, to, propertyId, unitId, leaseId, renterId)));
    }

    @GetMapping("/ledger/account/{accountId}")
    public ResponseEntity<AccountLedgerDTO> accountLedger(@PathVariable UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID propertyId, @RequestParam(required = false) UUID unitId,
            @RequestParam(required = false) UUID leaseId, @RequestParam(required = false) UUID renterId) {
        return ResponseEntity.ok(service.accountLedger(accountId, new LedgerFilter(from, to, propertyId, unitId, leaseId, renterId)));
    }

    @GetMapping("/ledger/renter/{renterId}")
    public ResponseEntity<List<AccountLedgerDTO>> renterLedger(@PathVariable UUID renterId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.renterLedger(renterId, from, to));
    }

    @GetMapping("/ledger/vendor/{vendorId}")
    public ResponseEntity<AccountLedgerDTO> vendorLedger(@PathVariable UUID vendorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.vendorLedger(vendorId, from, to));
    }

    @GetMapping("/trial-balance")
    public ResponseEntity<List<TrialBalanceRowDTO>> trialBalance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) UUID propertyId) {
        return ResponseEntity.ok(service.trialBalance(asOf, propertyId));
    }
}
```

`GET /api/v1/finance/ledger/vendor/{vendorId}` already exists on `FinancialTransactionController` — that controller is deleted in Task 15; until then keep the new one on the same path and delete the old mapping method now to avoid an ambiguous-mapping boot failure.

- [ ] **Step 5: Run, commit**

Run: `./gradlew test --tests '*LedgerQueryServiceIT'` → PASS.

```bash
git add backend/src
git commit -m "feat(ledger): account/general/renter/vendor ledgers and trial balance queries

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 9: Journal API — manual JV, list, detail, reverse

**Files:**
- Create: `api/dto/ledger/ManualJournalRequest.java`, `JournalEntryDTO.java`, `JournalLineDTO.java`, `ReverseRequest.java`
- Create: `core/service/ledger/JournalService.java`, `api/JournalController.java`
- Test: `api/JournalControllerIT.java` (HTTP, spoofed proxy headers as in `UserControllerRoleAuthorizationTest`)

**Interfaces:**
- DTOs:
  ```java
  record ManualJournalRequest(LocalDate entryDate, String narration, UUID propertyId, List<Line> lines) {
      record Line(UUID accountId, BigDecimal debit, BigDecimal credit, String narration, UUID unitId, UUID leaseId, UUID renterId) {}
  }
  record ReverseRequest(LocalDate date, String reason) {}
  record JournalLineDTO(int lineNo, UUID accountId, String accountCode, String accountName, BigDecimal debit, BigDecimal credit,
                        String narration, UUID propertyId, UUID unitId, UUID leaseId, UUID renterId, UUID chequeId) {}
  record JournalEntryDTO(UUID id, String entryNumber, String docType, LocalDate entryDate, String narration, String status,
                         UUID propertyId, UUID unitId, UUID leaseId, UUID renterId, String sourceType, UUID sourceId,
                         UUID reversalOfId, UUID reversedById, UUID importBatchId, UUID postedBy, Instant postedAt,
                         BigDecimal total, List<JournalLineDTO> lines) {}
  ```
- Endpoints:
  - `GET /api/v1/finance/journals?docType&from&to&propertyId&leaseId&page&size` → Spring `Page<JournalEntryDTO>` (lines omitted, `lines: []`) — read roles: SUPER_ADMIN, TENANT_ADMIN, ACCOUNTANT
  - `GET /api/v1/finance/journals/{id}` → `JournalEntryDTO` with lines
  - `POST /api/v1/finance/journals` body `ManualJournalRequest` → 201 `JournalEntryDTO` — SUPER_ADMIN, TENANT_ADMIN, ACCOUNTANT
  - `POST /api/v1/finance/journals/{id}/reverse` body `ReverseRequest` → `JournalEntryDTO` (the reversal) — same roles
  - `GET /api/v1/finance/journals/doc-types` → `List<String>`

- [ ] **Step 1: Failing HTTP test**

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class JournalControllerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;

    UUID tenantId; User accountant; User manager; String bankId; String capitalId;

    @BeforeEach void setUp() {
        LandlordOrg org = new LandlordOrg(); org.setName("JC-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts(); propertyAccounts.seedDefaultTemplateAndDefaults();
        bankId = accounts.createLeaf("ENBD Main", accounts.getAccountByCode("A-02-02"), null).getId().toString();
        capitalId = accounts.getAccountByCode("F-01").getId().toString();
        accountant = user(UserRole.ACCOUNTANT); manager = user(UserRole.PROPERTY_MANAGER);
        TenantContextHolder.clear();
    }

    private User user(UserRole role) {
        User u = new User(); u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setRole(role); u.setTenantId(tenantId); u.setPasswordHash("x"); u.setFullName(role.name());  // match User's required fields
        return userRepo.save(u);
    }

    private RestClient.RequestBodySpec as(User caller, String method, String uri) {
        RestClient c = RestClient.builder().baseUrl("http://localhost:" + port).build();
        RestClient.RequestBodySpec spec = (method.equals("POST") ? c.post() : c.get()).uri(uri).contentType(MediaType.APPLICATION_JSON) instanceof RestClient.RequestBodySpec s ? s : null;
        return spec.header("X-User-Id", caller.getId().toString()).header("X-User-Role", caller.getRole().name())
                .header("X-Tenant-Id", tenantId.toString()).header("X-User-Tenant-Id", tenantId.toString());
    }

    private Map<String, Object> jv(String amount) {
        return Map.of("entryDate", "2026-09-17", "narration", "Capital injection", "lines", List.of(
                Map.of("accountId", bankId, "debit", amount, "credit", 0),
                Map.of("accountId", capitalId, "debit", 0, "credit", amount)));
    }

    @Test
    void accountantPostsListsReadsAndReversesAManualJournal() {
        Map<?, ?> posted = as(accountant, "POST", "/api/v1/finance/journals").body(jv("5000")).retrieve().body(Map.class);
        assertThat(posted.get("entryNumber")).isEqualTo("JV-26/1");
        assertThat(((List<?>) posted.get("lines"))).hasSize(2);
        String id = (String) posted.get("id");

        Map<?, ?> page = as(accountant, "GET", "/api/v1/finance/journals?docType=JV").retrieve().body(Map.class);
        assertThat(((List<?>) page.get("content"))).hasSize(1);

        Map<?, ?> detail = as(accountant, "GET", "/api/v1/finance/journals/" + id).retrieve().body(Map.class);
        assertThat(detail.get("total")).isEqualTo(5000.0);

        Map<?, ?> rev = as(accountant, "POST", "/api/v1/finance/journals/" + id + "/reverse")
                .body(Map.of("date", "2026-09-18", "reason", "typo")).retrieve().body(Map.class);
        assertThat(rev.get("reversalOfId")).isEqualTo(id);
        assertThat(as(accountant, "GET", "/api/v1/finance/journals/" + id).retrieve().body(Map.class).get("status")).isEqualTo("REVERSED");
    }

    @Test
    void propertyManagerIsForbidden() {
        assertThatThrownBy(() -> as(manager, "POST", "/api/v1/finance/journals").body(jv("10")).retrieve().body(Map.class))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
        assertThatThrownBy(() -> as(manager, "GET", "/api/v1/finance/journals").retrieve().body(Map.class))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void unbalancedManualJournalIs400WithMessage() {
        Map<String, Object> bad = Map.of("entryDate", "2026-09-17", "narration", "x", "lines", List.of(
                Map.of("accountId", bankId, "debit", 10, "credit", 0), Map.of("accountId", capitalId, "debit", 0, "credit", 9)));
        assertThatThrownBy(() -> as(accountant, "POST", "/api/v1/finance/journals").body(bad).retrieve().body(Map.class))
                .isInstanceOf(HttpClientErrorException.BadRequest.class).hasMessageContaining("not balanced");
    }
}
```

Copy the `createAs`/header helper style from `UserControllerRoleAuthorizationTest` rather than the compressed `as()` above if the `instanceof` cast is awkward — the point is the four `X-*` headers. Match `User`'s real setters.

- [ ] **Step 2: Run — compile failure.**

- [ ] **Step 3: Service + controller**

```java
package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.dto.ledger.*;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.core.service.ledger.PostingRequest.*;

@Service
public class JournalService {

    private final PostingService posting;
    private final JournalEntryRepository entries;
    private final JournalLineRepository lines;

    public JournalService(PostingService posting, JournalEntryRepository entries, JournalLineRepository lines) {
        this.posting = posting; this.entries = entries; this.lines = lines;
    }

    @Transactional
    public JournalEntryDTO postManual(ManualJournalRequest r) {
        if (r.lines() == null || r.lines().isEmpty()) throw new BusinessRuleViolationException("At least two lines are required");
        List<Line> out = new ArrayList<>();
        for (ManualJournalRequest.Line l : r.lines()) {
            BigDecimal dr = l.debit() == null ? BigDecimal.ZERO : l.debit();
            BigDecimal cr = l.credit() == null ? BigDecimal.ZERO : l.credit();
            if (dr.signum() > 0 && cr.signum() > 0) throw new BusinessRuleViolationException("A line is either debit or credit, not both");
            if (dr.signum() <= 0 && cr.signum() <= 0) throw new BusinessRuleViolationException("Each line needs a positive debit or credit");
            Dimensions dims = new Dimensions(r.propertyId(), l.unitId(), l.leaseId(), l.renterId(), null);
            Line line = dr.signum() > 0 ? dr(l.accountId(), dr) : cr(l.accountId(), cr);
            out.add(line.withDims(dims).withNarration(l.narration()));
        }
        JournalEntry e = posting.post(new PostingRequest(JournalDocType.JV, r.entryDate(), r.narration(),
                Dimensions.ofProperty(r.propertyId()), JournalSourceType.MANUAL, null, null, out));
        return toDto(e, true);
    }

    @Transactional
    public JournalEntryDTO reverse(UUID id, ReverseRequest r) {
        LocalDate date = r.date() == null ? LocalDate.now() : r.date();
        return toDto(posting.reverse(id, date, r.reason()), true);
    }

    @Transactional(readOnly = true)
    public JournalEntryDTO get(UUID id) {
        return toDto(entries.findById(id).orElseThrow(() -> new NotFoundException("Journal entry not found")), true);
    }

    @Transactional(readOnly = true)
    public Page<JournalEntryDTO> search(JournalDocType docType, LocalDate from, LocalDate to, UUID propertyId, UUID leaseId, Pageable pageable) {
        return entries.search(docType, from, to, propertyId, leaseId, pageable).map(e -> toDto(e, false));
    }

    JournalEntryDTO toDto(JournalEntry e, boolean withLines) {
        List<JournalLineDTO> ls = List.of();
        BigDecimal total = BigDecimal.ZERO;
        if (withLines) {
            ls = new ArrayList<>();
            for (JournalLine l : lines.findByEntry_IdOrderByLineNoAsc(e.getId())) {
                total = total.add(l.getDebit());
                ls.add(new JournalLineDTO(l.getLineNo(), l.getAccount().getId(), l.getAccount().getCode(), l.getAccount().getName(),
                        l.getDebit(), l.getCredit(), l.getNarration(), l.getPropertyId(), l.getUnitId(), l.getLeaseId(), l.getRenterId(), l.getChequeId()));
            }
        }
        return new JournalEntryDTO(e.getId(), e.getEntryNumber(), e.getDocType().name(), e.getEntryDate(), e.getNarration(), e.getStatus().name(),
                e.getPropertyId(), e.getUnitId(), e.getLeaseId(), e.getRenterId(),
                e.getSourceType() == null ? null : e.getSourceType().name(), e.getSourceId(),
                e.getReversalOfId(), e.getReversedById(), e.getImportBatchId(), e.getPostedBy(), e.getPostedAt(), total, ls);
    }
}
```

For the list view a total per entry is useful without loading lines: add to `JournalLineRepository` `@Query("select coalesce(sum(l.debit),0) from JournalLine l where l.entry.id = :entryId") BigDecimal totalDebit(UUID entryId);` and use it in `toDto` when `!withLines`.

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ledger.*;
import com.datagami.rentaxis.core.service.ledger.JournalService;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/journals")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
public class JournalController {

    private final JournalService service;
    public JournalController(JournalService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<Page<JournalEntryDTO>> list(
            @RequestParam(required = false) JournalDocType docType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID propertyId, @RequestParam(required = false) UUID leaseId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(service.search(docType, from, to, propertyId, leaseId, PageRequest.of(page, Math.min(size, 200))));
    }

    @GetMapping("/doc-types")
    public ResponseEntity<List<String>> docTypes() { return ResponseEntity.ok(Arrays.stream(JournalDocType.values()).map(Enum::name).toList()); }

    @GetMapping("/{id}")
    public ResponseEntity<JournalEntryDTO> get(@PathVariable UUID id) { return ResponseEntity.ok(service.get(id)); }

    @PostMapping
    public ResponseEntity<JournalEntryDTO> postManual(@RequestBody ManualJournalRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.postManual(r));
    }

    @PostMapping("/{id}/reverse")
    public ResponseEntity<JournalEntryDTO> reverse(@PathVariable UUID id, @RequestBody ReverseRequest r) {
        return ResponseEntity.ok(service.reverse(id, r));
    }
}
```

- [ ] **Step 4: Run, commit**

Run: `./gradlew test --tests '*JournalControllerIT'` → PASS.

```bash
git add backend/src
git commit -m "feat(ledger): manual journal voucher API with list, detail and reversal

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 10: Fiscal settings API

**Files:**
- Create: `api/FiscalSettingsController.java`, `api/dto/ledger/FiscalSettingsDTO.java`
- Test: `api/FiscalSettingsControllerIT.java`

**Interfaces:**
- `record FiscalSettingsDTO(int fiscalYearStartMonth, LocalDate booksStartDate, LocalDate booksLockedThrough)`
- `GET /api/v1/finance/fiscal-settings` → DTO; `PUT /api/v1/finance/fiscal-settings` body `{fiscalYearStartMonth?, booksStartDate?}` → DTO; `POST /api/v1/finance/fiscal-settings/lock` body `{through}` → DTO. Roles SUPER_ADMIN, TENANT_ADMIN, ACCOUNTANT.

- [ ] **Step 1: Test** — same HTTP harness as Task 9: `GET` returns `fiscalYearStartMonth = 1` and null dates for a fresh tenant; `PUT {"fiscalYearStartMonth": 6, "booksStartDate": "2026-10-01"}` returns month 6, start 2026-10-01 and `booksLockedThrough = 2026-09-30`; `POST lock {"through": "2026-08-31"}` → 400 (backwards); `POST lock {"through": "2026-10-31"}` → 200 with that date; PROPERTY_MANAGER → 403.

- [ ] **Step 2: Controller**

```java
@RestController
@RequestMapping("/api/v1/finance/fiscal-settings")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
public class FiscalSettingsController {

    private final TenantFiscalSettingsService service;
    public FiscalSettingsController(TenantFiscalSettingsService service) { this.service = service; }

    public record UpdateBody(Integer fiscalYearStartMonth, LocalDate booksStartDate) {}
    public record LockBody(LocalDate through) {}

    private static FiscalSettingsDTO dto(TenantFiscalSettings s) {
        return new FiscalSettingsDTO(s.getFiscalYearStartMonth(), s.getBooksStartDate(), s.getBooksLockedThrough());
    }

    @GetMapping public ResponseEntity<FiscalSettingsDTO> get() { return ResponseEntity.ok(dto(service.get())); }

    @PutMapping public ResponseEntity<FiscalSettingsDTO> update(@RequestBody UpdateBody b) {
        TenantFiscalSettings s = service.get();
        if (b.fiscalYearStartMonth() != null) s = service.setFiscalYearStartMonth(b.fiscalYearStartMonth());
        if (b.booksStartDate() != null) s = service.setBooksStartDate(b.booksStartDate());
        return ResponseEntity.ok(dto(s));
    }

    @PostMapping("/lock") public ResponseEntity<FiscalSettingsDTO> lock(@RequestBody LockBody b) {
        if (b.through() == null) throw new BusinessRuleViolationException("'through' is required");
        return ResponseEntity.ok(dto(service.lockThrough(b.through())));
    }
}
```

- [ ] **Step 3: Run, commit**

```bash
git add backend/src
git commit -m "feat(ledger): fiscal settings and period lock API

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 11: Web — typed ledger API client, RBAC role, i18n keys

**Files:**
- Create: `web/src/lib/api/ledger.ts`
- Modify: `web/src/lib/rbac.ts`, `web/messages/en.json`, `web/messages/ar.json`
- Test: `web/src/lib/api/__tests__/ledger.test.ts`

**Interfaces:**
- Produces (all in `ledger.ts`, every function `throwIfNotOk`s and returns parsed JSON):
  ```ts
  export type AccountRole = "RENT_RECEIVABLE" | "ADVANCE_RENT" | ... (all 21)
  export type JournalDocType = "TCO"|"TCR"|"PDR"|"CRT"|"CBR"|"CIL"|"RCP"|"STL"|"PEN"|"PISR"|"BPV"|"OB"|"JV"
  export type Account = { id; code; name; nameEn; nameAr; alias; accountType; accountSubType; parentId; propertyId; system; group; active; displayOrder; description }
  export type RoleMapping = { role: AccountRole; accountId: string|null; accountCode: string|null; accountName: string|null; inherited: boolean }
  export type TemplateRow = { role: AccountRole; namePattern: string; parentAccountId: string; parentCode: string; enabled: boolean }
  export type LedgerRow = { entryId; entryNumber; entryDate; docType; particular; narration; debit; credit; balance; propertyId; unitId; leaseId; renterId; chequeId }
  export type AccountLedger = { accountId; accountCode; accountName; accountType; openingBalance; rows: LedgerRow[]; totalDebit; totalCredit; closingBalance; truncated }
  export type TrialBalanceRow = { accountId; code; name; accountType; parentId; propertyId; debit; credit; balance }
  export type JournalLine = { lineNo; accountId; accountCode; accountName; debit; credit; narration; propertyId; unitId; leaseId; renterId; chequeId }
  export type JournalEntry = { id; entryNumber; docType; entryDate; narration; status: "POSTED"|"REVERSED"; propertyId; ...; reversalOfId; reversedById; importBatchId; postedBy; postedAt; total; lines: JournalLine[] }
  export type Page<T> = { content: T[]; totalElements: number; totalPages: number; number: number; size: number }
  export type FiscalSettings = { fiscalYearStartMonth: number; booksStartDate: string|null; booksLockedThrough: string|null }

  export const ledgerApi = {
    accounts: { list(), tree(), children(id), create(body), update(id, body), remove(id), import(file) },
    propertyAccounts: { get(propertyId), generate(propertyId), set(propertyId, role, accountId), clear(propertyId, role) },
    template: { get(), save(rows) },
    defaults: { get(), set(role, accountId) },
    roles(): Promise<{role: AccountRole; propertyScoped: boolean}[]>,
    ledger: { general(q: {accountIds?: string[]; from?; to?; propertyId?; unitId?; leaseId?; renterId?}), account(id, q), renter(renterId, q), vendor(vendorId, q) },
    trialBalance(q: {asOf?; propertyId?}),
    journals: { list(q: {docType?; from?; to?; propertyId?; leaseId?; page; size}), get(id), postManual(body), reverse(id, body), docTypes() },
    fiscal: { get(), update(body), lock(through) },
  }
  export function fmtAmount(n: number): string          // "61,000.00"
  export function fmtBalance(n: number): string         // "61,000.00 Dr" / "3,000.00 Cr" / "0.00"
  ```
- `rbac.ts`: `UserRole` union gains `'ACCOUNTANT'`; `ROLE_RANK.ACCOUNTANT = 2`; `PERMISSIONS.canAccessFinance` gains `'ACCOUNTANT'`; new `canPostJournals: ['SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT']`, `canManageAccountSetup: ['SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT']`; `getRoleLabelKey('ACCOUNTANT')` → `'accountant'` and `Roles.accountant` added to both message files.

- [ ] **Step 1: Failing unit test for the formatters and URL building**

```ts
// web/src/lib/api/__tests__/ledger.test.ts
import { describe, it, expect, vi, beforeEach } from "vitest";
import { ledgerApi, fmtAmount, fmtBalance } from "../ledger";

describe("ledger formatters", () => {
  it("formats amounts with two decimals and thousands separators", () => {
    expect(fmtAmount(61000)).toBe("61,000.00");
    expect(fmtAmount(978.082)).toBe("978.08");
  });
  it("formats signed balances as Dr/Cr", () => {
    expect(fmtBalance(61000)).toBe("61,000.00 Dr");
    expect(fmtBalance(-3000)).toBe("3,000.00 Cr");
    expect(fmtBalance(0)).toBe("0.00");
  });
});

describe("ledgerApi urls", () => {
  beforeEach(() => { vi.stubGlobal("fetch", vi.fn(async () => new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } }))); });
  it("builds the general ledger query", async () => {
    await ledgerApi.ledger.general({ accountIds: ["a", "b"], from: "2026-09-01", to: "2026-09-30", propertyId: "p" });
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/finance/ledger?accountIds=a%2Cb&from=2026-09-01&to=2026-09-30&propertyId=p", expect.anything());
  });
  it("omits undefined params", async () => {
    await ledgerApi.trialBalance({ asOf: "2026-09-30" });
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/finance/trial-balance?asOf=2026-09-30", expect.anything());
  });
});
```

- [ ] **Step 2: Run** — `cd web && npx vitest run src/lib/api/__tests__/ledger.test.ts` → fails (module missing).

- [ ] **Step 3: Implement `ledger.ts`**

```ts
import { throwIfNotOk } from "@/lib/api/facilities";

const BASE = "/api/proxy/v1";

function qs(params: Record<string, string | number | string[] | undefined | null>): string {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === "") continue;
    sp.set(k, Array.isArray(v) ? v.join(",") : String(v));
  }
  const s = sp.toString();
  return s ? `?${s}` : "";
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { method: "GET" });
  await throwIfNotOk(res);
  return res.json();
}
async function send<T>(method: "POST" | "PUT" | "DELETE", path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method, headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  await throwIfNotOk(res);
  return res.status === 204 ? (undefined as T) : res.json();
}

// ---- types (see Interfaces block; write them out in full here) ----

export const ledgerApi = {
  accounts: {
    list: () => get<Account[]>("/finance/accounts"),
    tree: () => get<Account[]>("/finance/accounts/tree"),
    children: (id: string) => get<Account[]>(`/finance/accounts/${id}/children`),
    create: (body: CreateAccountBody) => send<Account>("POST", "/finance/accounts", body),
    update: (id: string, body: UpdateAccountBody) => send<Account>("PUT", `/finance/accounts/${id}`, body),
    remove: (id: string) => send<void>("DELETE", `/finance/accounts/${id}`),
    import: async (file: File) => { const fd = new FormData(); fd.append("file", file);
      const res = await fetch(`${BASE}/finance/accounts/import`, { method: "POST", body: fd }); await throwIfNotOk(res); return res.json() as Promise<Account[]>; },
  },
  propertyAccounts: {
    get: (propertyId: string) => get<RoleMapping[]>(`/properties/${propertyId}/accounts`),
    generate: (propertyId: string) => send<RoleMapping[]>("POST", `/properties/${propertyId}/accounts/generate`),
    set: (propertyId: string, role: AccountRole, accountId: string) => send<RoleMapping>("PUT", `/properties/${propertyId}/accounts/${role}`, { accountId }),
    clear: (propertyId: string, role: AccountRole) => send<void>("DELETE", `/properties/${propertyId}/accounts/${role}`),
  },
  template: { get: () => get<TemplateRow[]>("/finance/account-template"), save: (rows: TemplateRow[]) => send<TemplateRow[]>("PUT", "/finance/account-template", rows) },
  defaults: { get: () => get<RoleMapping[]>("/finance/default-accounts"), set: (role: AccountRole, accountId: string) => send<RoleMapping>("PUT", `/finance/default-accounts/${role}`, { accountId }) },
  roles: () => get<{ role: AccountRole; propertyScoped: boolean }[]>("/finance/account-roles"),
  ledger: {
    general: (q: LedgerQuery) => get<AccountLedger[]>(`/finance/ledger${qs(q)}`),
    account: (id: string, q: LedgerQuery) => get<AccountLedger>(`/finance/ledger/account/${id}${qs(q)}`),
    renter: (renterId: string, q: { from?: string; to?: string }) => get<AccountLedger[]>(`/finance/ledger/renter/${renterId}${qs(q)}`),
    vendor: (vendorId: string, q: { from?: string; to?: string }) => get<AccountLedger>(`/finance/ledger/vendor/${vendorId}${qs(q)}`),
  },
  trialBalance: (q: { asOf?: string; propertyId?: string }) => get<TrialBalanceRow[]>(`/finance/trial-balance${qs(q)}`),
  journals: {
    list: (q: { docType?: JournalDocType | ""; from?: string; to?: string; propertyId?: string; leaseId?: string; page: number; size: number }) => get<Page<JournalEntry>>(`/finance/journals${qs(q)}`),
    get: (id: string) => get<JournalEntry>(`/finance/journals/${id}`),
    postManual: (body: ManualJournalBody) => send<JournalEntry>("POST", "/finance/journals", body),
    reverse: (id: string, body: { date: string; reason: string }) => send<JournalEntry>("POST", `/finance/journals/${id}/reverse`, body),
    docTypes: () => get<JournalDocType[]>("/finance/journals/doc-types"),
  },
  fiscal: {
    get: () => get<FiscalSettings>("/finance/fiscal-settings"),
    update: (body: { fiscalYearStartMonth?: number; booksStartDate?: string }) => send<FiscalSettings>("PUT", "/finance/fiscal-settings", body),
    lock: (through: string) => send<FiscalSettings>("POST", "/finance/fiscal-settings/lock", { through }),
  },
};

const nf = new Intl.NumberFormat("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
export function fmtAmount(n: number): string { return nf.format(n); }
export function fmtBalance(n: number): string {
  if (Math.abs(n) < 0.005) return "0.00";
  return n > 0 ? `${nf.format(n)} Dr` : `${nf.format(-n)} Cr`;
}
```

Write every `type` from the Interfaces block in full (`LedgerQuery = { accountIds?: string[]; from?: string; to?: string; propertyId?: string; unitId?: string; leaseId?: string; renterId?: string }`, `CreateAccountBody`, `UpdateAccountBody`, `ManualJournalBody = { entryDate: string; narration: string; propertyId?: string; lines: { accountId: string; debit: number; credit: number; narration?: string }[] }`).

- [ ] **Step 4: `rbac.ts` + messages**

Add `'ACCOUNTANT'` to `UserRole`, `ROLE_RANK` (2), `canAccessFinance`, `canManageLeases` (accountant needs to post leases in Plan 2 — leave `canManageLeases` unchanged for now), and the two new permissions. In `en.json`/`ar.json` add `Roles.accountant` ("Accountant" / "محاسب") and a new `Ledger` namespace with these keys (Arabic values in `ar.json`):

```json
"Ledger": {
  "generalLedger": "General Ledger", "tenantLedger": "Tenant Ledger", "vendorLedger": "Vendor Ledger", "trialBalance": "Trial Balance",
  "journals": "Journal Vouchers", "newJournal": "New Journal Voucher", "journalDetail": "Journal Voucher",
  "docDate": "Doc Date", "docNo": "Doc No", "docType": "Doc Type", "particular": "Particular", "narration": "Narration",
  "debit": "Debit", "credit": "Credit", "balance": "Balance", "unit": "Unit", "tower": "Tower", "tenant": "Tenant", "chequeNo": "Chq No",
  "openingBalance": "Opening Balance", "closingBalance": "Closing Balance", "subTotal": "Sub Total", "reportTotal": "Report Total",
  "from": "From", "to": "To", "asOf": "As of", "accounts": "Accounts", "allAccountsWithActivity": "All accounts with activity",
  "selectRenter": "Select tenant", "selectVendor": "Select vendor", "selectProperty": "All properties",
  "noRows": "No entries for this selection", "truncated": "Showing the first {n} rows — narrow the date range",
  "status": "Status", "posted": "Posted", "reversed": "Reversed", "reverse": "Reverse", "reverseReason": "Reason for reversal",
  "reverseDate": "Reversal date", "reversalOf": "Reversal of {number}", "reversedBy": "Reversed by {number}", "confirmReverse": "Post a reversing entry for {number}? The original stays in the ledger.",
  "account": "Account", "addLine": "Add line", "removeLine": "Remove", "totalDebit": "Total debit", "totalCredit": "Total credit",
  "unbalanced": "Debits and credits must be equal", "postJournal": "Post", "journalPosted": "Journal {number} posted",
  "propertyAccounts": "Ledger accounts", "propertyAccountsDesc": "Which ledger account each role posts to for this property.",
  "generateMissing": "Generate missing accounts", "inherited": "Default", "unmapped": "Not mapped", "role": "Role", "changeAccount": "Change",
  "remapWarning": "Posted entries keep their account; only future postings move.",
  "accountTemplate": "Property account template", "accountTemplateDesc": "Accounts created automatically for every new property.",
  "namePattern": "Name pattern", "parentGroup": "Parent group", "enabled": "Enabled", "patternHint": "Use {property} for the property name",
  "defaultAccounts": "Default accounts", "defaultAccountsDesc": "Tenant-wide accounts used when a property has no mapping.",
  "fiscal": "Fiscal year & period lock", "fiscalYearStartMonth": "Fiscal year starts in", "booksStartDate": "Books start date",
  "booksLockedThrough": "Books locked through", "lockPeriod": "Lock period", "lockThrough": "Lock through", "lockWarning": "Nothing can be posted on or before this date.",
  "export": "Export", "print": "Print", "propertyFilter": "Property", "alias": "Alias", "parentAccount": "Parent account", "propertyTag": "Property"
}
```

- [ ] **Step 5: Run tests, commit**

`cd web && npx vitest run src/lib/api/__tests__/ledger.test.ts && npx tsc --noEmit` → PASS.

```bash
git add web/src/lib web/messages
git commit -m "feat(web): typed ledger API client, ACCOUNTANT role, Ledger i18n namespace

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 12: Web — Chart of Accounts page on parentId, property Accounts tab, template + default-accounts settings

**Files:**
- Modify: `web/src/app/[locale]/dashboard/finance/accounts/page.tsx`, `.../accounts/__tests__/page.test.tsx`
- Create: `web/src/components/finance/PropertyAccountsTab.tsx`, `web/src/components/finance/AccountPicker.tsx`
- Modify: property detail page (`web/src/app/[locale]/dashboard/properties/[id]/page.tsx` — confirm the path) to add an **Accounts** tab rendering `<PropertyAccountsTab propertyId={id} />` when `hasPermission(role, 'canManageAccountSetup')`
- Create: `web/src/app/[locale]/dashboard/settings/account-template/page.tsx`
- Test: `web/src/components/finance/__tests__/PropertyAccountsTab.test.tsx`

**Interfaces:**
- `AccountPicker({ value, onChange, accountType?, leafOnly = true, propertyId?, placeholder })` — searchable dropdown over `ledgerApi.accounts.list()` (cached in module scope for the page lifetime), shows `code — name`, filters by `accountType` and `group === false`.
- `PropertyAccountsTab({ propertyId })` — table Role | Account | Source | Actions; rows from `ledgerApi.propertyAccounts.get`; *Generate missing accounts* button; per-row *Change* opens `AccountPicker` inline and calls `set`; *Use default* calls `clear`. Shows `remapWarning` in the picker.

- [ ] **Step 1: Failing component test**

```tsx
// web/src/components/finance/__tests__/PropertyAccountsTab.test.tsx
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { vi, describe, it, expect, beforeEach } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import PropertyAccountsTab from "../PropertyAccountsTab";

const mappings = [
  { role: "RENT_RECEIVABLE", accountId: "a1", accountCode: "100001", accountName: "Rent Receivable - Tulip 7", inherited: false },
  { role: "ADVANCE_RENT", accountId: null, accountCode: null, accountName: null, inherited: false },
  { role: "CASH", accountId: "c1", accountCode: "A-02-05-001", accountName: "Cash Account", inherited: true },
];

vi.mock("@/lib/api/ledger", () => ({
  ledgerApi: {
    propertyAccounts: { get: vi.fn(async () => mappings), generate: vi.fn(async () => mappings), set: vi.fn(), clear: vi.fn() },
    accounts: { list: vi.fn(async () => []) },
  },
}));

const wrap = (ui: React.ReactNode) => <NextIntlClientProvider locale="en" messages={en}>{ui}</NextIntlClientProvider>;

describe("PropertyAccountsTab", () => {
  it("renders one row per role with mapped, unmapped and inherited states", async () => {
    render(wrap(<PropertyAccountsTab propertyId="p1" />));
    await waitFor(() => expect(screen.getByText("Rent Receivable - Tulip 7")).toBeInTheDocument());
    expect(screen.getByText("Not mapped")).toBeInTheDocument();
    expect(screen.getByText("Default")).toBeInTheDocument();
  });
  it("calls generate when the button is pressed", async () => {
    const { ledgerApi } = await import("@/lib/api/ledger");
    render(wrap(<PropertyAccountsTab propertyId="p1" />));
    await waitFor(() => screen.getByText("Generate missing accounts"));
    fireEvent.click(screen.getByText("Generate missing accounts"));
    await waitFor(() => expect(ledgerApi.propertyAccounts.generate).toHaveBeenCalledWith("p1"));
  });
});
```

- [ ] **Step 2: Run** — `npx vitest run src/components/finance` → fails.

- [ ] **Step 3: Components**

`AccountPicker.tsx`:

```tsx
"use client";
import { useEffect, useMemo, useState } from "react";
import { ledgerApi, type Account } from "@/lib/api/ledger";

let cache: Promise<Account[]> | null = null;
export function loadAccounts(): Promise<Account[]> { if (!cache) cache = ledgerApi.accounts.list(); return cache; }
export function invalidateAccounts() { cache = null; }

type Props = { value: string | null; onChange: (id: string) => void; accountType?: string; leafOnly?: boolean; placeholder?: string; autoFocus?: boolean };

export default function AccountPicker({ value, onChange, accountType, leafOnly = true, placeholder, autoFocus }: Props) {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [q, setQ] = useState("");
  useEffect(() => { loadAccounts().then(setAccounts).catch(() => setAccounts([])); }, []);
  const options = useMemo(() => accounts
    .filter(a => (!leafOnly || !a.group) && a.active && (!accountType || a.accountType === accountType))
    .filter(a => !q || `${a.code} ${a.name} ${a.alias ?? ""}`.toLowerCase().includes(q.toLowerCase()))
    .slice(0, 50), [accounts, q, accountType, leafOnly]);
  const selected = accounts.find(a => a.id === value);
  return (
    <div className="relative">
      <input autoFocus={autoFocus} className="w-full bg-input border border-border rounded-lg px-3 py-2 text-xs"
        placeholder={placeholder} value={q || (selected ? `${selected.code} — ${selected.name}` : "")}
        onChange={e => setQ(e.target.value)} onFocus={() => setQ("")} />
      {q && (
        <ul className="absolute z-20 mt-1 w-full max-h-64 overflow-auto bg-surface border border-border rounded-lg shadow-lg">
          {options.map(a => (
            <li key={a.id}><button type="button" className="w-full text-left px-3 py-2 text-xs hover:bg-input"
              onClick={() => { onChange(a.id); setQ(""); }}>
              <span className="font-mono text-muted mr-2">{a.code}</span>{a.name}
            </button></li>
          ))}
          {options.length === 0 && <li className="px-3 py-2 text-xs text-muted">—</li>}
        </ul>
      )}
    </div>
  );
}
```

`PropertyAccountsTab.tsx`:

```tsx
"use client";
import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2, Sparkles } from "lucide-react";
import { ledgerApi, type RoleMapping, type AccountRole } from "@/lib/api/ledger";
import { ApiError } from "@/lib/api/facilities";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import AccountPicker, { invalidateAccounts } from "./AccountPicker";

export default function PropertyAccountsTab({ propertyId }: { propertyId: string }) {
  const t = useTranslations("Ledger");
  const [rows, setRows] = useState<RoleMapping[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<AccountRole | null>(null);

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try { setRows(await ledgerApi.propertyAccounts.get(propertyId)); }
    catch (e) { setError(e instanceof ApiError ? e.message : "Failed to load"); }
    finally { setLoading(false); }
  }, [propertyId]);
  useEffect(() => { load(); }, [load]);

  const generate = async () => {
    setBusy(true); setError(null);
    try { setRows(await ledgerApi.propertyAccounts.generate(propertyId)); invalidateAccounts(); }
    catch (e) { setError(e instanceof ApiError ? e.message : "Failed"); }
    finally { setBusy(false); }
  };
  const set = async (role: AccountRole, accountId: string) => {
    setBusy(true); setError(null);
    try { await ledgerApi.propertyAccounts.set(propertyId, role, accountId); setEditing(null); await load(); }
    catch (e) { setError(e instanceof ApiError ? e.message : "Failed"); }
    finally { setBusy(false); }
  };
  const clear = async (role: AccountRole) => {
    setBusy(true); setError(null);
    try { await ledgerApi.propertyAccounts.clear(propertyId, role); await load(); }
    catch (e) { setError(e instanceof ApiError ? e.message : "Failed"); }
    finally { setBusy(false); }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-start justify-between gap-4">
        <div><h3 className="text-sm font-semibold text-foreground">{t("propertyAccounts")}</h3><p className="text-xs text-muted">{t("propertyAccountsDesc")}</p></div>
        <button onClick={generate} disabled={busy} className="flex items-center gap-1.5 px-3 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-medium disabled:opacity-50">
          {busy ? <Loader2 size={13} className="animate-spin" /> : <Sparkles size={13} />}{t("generateMissing")}
        </button>
      </div>
      {error && <LoadErrorBanner message={error} onRetry={load} />}
      {loading ? <div className="bg-input rounded-xl h-40 animate-pulse" /> : (
        <div className="bg-surface border border-border rounded-xl overflow-hidden">
          <table className="w-full">
            <thead><tr className="bg-input/50">
              {[t("role"), t("account"), t("status"), ""].map((h, i) => <th key={i} className="text-left px-5 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{h}</th>)}
            </tr></thead>
            <tbody className="divide-y divide-border">
              {rows.map(r => (
                <tr key={r.role} className="hover:bg-input/30">
                  <td className="px-5 py-3 text-xs font-medium">{r.role.replaceAll("_", " ")}</td>
                  <td className="px-5 py-3 text-xs">
                    {editing === r.role ? <div className="max-w-md"><AccountPicker autoFocus value={r.accountId} onChange={id => set(r.role, id)} /><p className="text-[10px] text-muted mt-1">{t("remapWarning")}</p></div>
                      : r.accountId ? <><span className="font-mono text-muted mr-2">{r.accountCode}</span>{r.accountName}</> : <span className="text-warning">{t("unmapped")}</span>}
                  </td>
                  <td className="px-5 py-3 text-xs">{r.inherited ? <span className="bg-input text-muted border border-border px-2 py-0.5 rounded-lg text-[10px] font-bold">{t("inherited")}</span> : null}</td>
                  <td className="px-5 py-3 text-xs text-right whitespace-nowrap">
                    <button className="text-primary hover:underline mr-3" onClick={() => setEditing(editing === r.role ? null : r.role)}>{t("changeAccount")}</button>
                    {r.accountId && !r.inherited && <button className="text-muted hover:underline" onClick={() => clear(r.role)}>{t("inherited")}</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 4: Chart of Accounts page**

In `finance/accounts/page.tsx`:
- `Account` type: replace `parentCode: string | null; hierarchyLevel: number;` with `parentId: string | null; propertyId: string | null; alias: string | null;`.
- `buildTree()`: group by `parentId` instead of `parentCode` (roots = `parentId === null`).
- Add form: replace the parent-code text input with `<AccountPicker leafOnly={false} …>` filtered to `group === true` (add a `groupOnly` prop mirroring `leafOnly`), and add optional **Property** select (fetch `/api/proxy/v1/properties` as the vendors page does) and **Alias** input. Body sent: `{ code, name, nameEn, nameAr, alias, accountType, accountSubType, description, parentId, propertyId, group }` — `code` may be blank (backend assigns the next numeric code).
- Edit form: add Alias + Property; body `{ name, nameEn, nameAr, alias, description, accountSubType, active, displayOrder, propertyId }`.
- Add a **Property** filter dropdown next to the type filter (client-side filter on `propertyId`, plus "tenant-wide" = null).
- Replace raw `fetch` calls with `ledgerApi.accounts.*`.
- Update `__tests__/page.test.tsx` fixtures from `parentCode` to `parentId`.

- [ ] **Step 5: Settings → Property account template + default accounts page**

`settings/account-template/page.tsx` ("use client"): two cards. **Template** table Role | Name pattern (text input) | Parent group (`AccountPicker leafOnly={false} groupOnly`) | Enabled (checkbox); Save button → `ledgerApi.template.save(rows)`. **Default accounts** table Role | Account (`AccountPicker`) | Save per row → `ledgerApi.defaults.set`. Both load on mount with `LoadErrorBanner` on failure. Page is gated by `hasPermission(role, 'canManageAccountSetup')` (render a "not allowed" card otherwise, like the settings pages that use `canConfigureFines`).

- [ ] **Step 6: Property detail Accounts tab** — find the property detail page's tab strip, add a tab labelled `t("Ledger.propertyAccounts")` that renders `<PropertyAccountsTab propertyId={id} />` behind `canManageAccountSetup`.

- [ ] **Step 7: Tests, lint, commit**

`cd web && npx vitest run src/components/finance src/app/\[locale\]/dashboard/finance/accounts && npx tsc --noEmit && npm run lint` → PASS.

```bash
git add web/src
git commit -m "feat(web): CoA on parentId with property/alias, property Accounts tab, account template settings

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 13: Web — General Ledger, Tenant Ledger, Trial Balance pages; Vendor Ledger link

**Files:**
- Create: `web/src/components/finance/LedgerTable.tsx`, `web/src/components/finance/LedgerFilters.tsx`
- Create: `web/src/app/[locale]/dashboard/finance/general-ledger/page.tsx`, `.../finance/tenant-ledger/page.tsx`, `.../finance/trial-balance/page.tsx`
- Modify: `web/src/app/[locale]/dashboard/finance/vendors/page.tsx` (Ledger action → `/dashboard/finance/general-ledger?vendorId=…`)
- Test: `web/src/components/finance/__tests__/LedgerTable.test.tsx`

**Interfaces:**
- `LedgerTable({ ledgers: AccountLedger[], showTenantColumns?: boolean })` — renders, per account, PACT's layout: an orange-ish account header band (`Account Code :: 166269   Name :: Rent Receivable - L'Olivier`), an opening-balance row when `openingBalance ≠ 0`, one row per `LedgerRow` (Doc Date | Doc No | Particular | Debit | Credit | Balance | Unit | Tenant), a **Sub Total** row (totalDebit / totalCredit / closingBalance), and a final **Report Total** row across all accounts. Amounts via `fmtAmount`, balances via `fmtBalance`. Doc No links to `/dashboard/finance/journals/{entryId}`. Unit/Tenant columns show ids resolved to names through a small lookup hook (`useNameLookup(kind)`) that fetches `/api/proxy/v1/units` and `/api/proxy/v1/renters` once and caches by id; if those list endpoints are paginated, fetch with `size=1000`.
- `LedgerFilters({ value, onChange, showAccounts?, showProperty?, showRenter? })` — From/To date inputs (default: first day of current month → today), Property select, Account multi-select (`AccountPicker` with chips), Renter select. Emits `LedgerQuery`.
- Pages:
  - **General Ledger**: filters + `ledgerApi.ledger.general(q)` → `LedgerTable`. Query params `?accountId=` / `?vendorId=` pre-select (vendor → `ledgerApi.ledger.vendor`). Export button downloads CSV built client-side from the rows.
  - **Tenant Ledger**: Renter select + dates → `ledgerApi.ledger.renter` → `LedgerTable showTenantColumns`, yellow "Tenant Name : …" band under each account header as in PACT.
  - **Trial Balance**: As-of date + Property select → `ledgerApi.trialBalance` → table Code | Name | Type | Debit | Credit grouped by `accountType` with subtotal per type and a grand total row; a red banner if Σdebit ≠ Σcredit.

- [ ] **Step 1: Failing test**

```tsx
// web/src/components/finance/__tests__/LedgerTable.test.tsx
import { render, screen } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import LedgerTable from "../LedgerTable";

vi.mock("../useNameLookup", () => ({ useNameLookup: () => ({ name: (id: string | null) => id ?? "" }) }));

const ledgers = [{
  accountId: "a", accountCode: "166269", accountName: "Rent Receivable - L'Olivier", accountType: "ASSET",
  openingBalance: 0, totalDebit: 64500, totalCredit: 13700, closingBalance: 50800, truncated: false,
  rows: [
    { entryId: "e1", entryNumber: "TCO-26/15", entryDate: "2026-09-11", docType: "TCO", particular: "Advance Rent - L'Olivier / Security Deposit L'Olivier", narration: "", debit: 64500, credit: 0, balance: 64500, propertyId: null, unitId: "u1", leaseId: null, renterId: "r1", chequeId: null },
    { entryId: "e2", entryNumber: "PDR-26/75", entryDate: "2026-09-11", docType: "PDR", particular: "PDC Receivable L'Olivier", narration: "Rent - 1st Installment", debit: 0, credit: 13700, balance: 50800, propertyId: null, unitId: "u1", leaseId: null, renterId: "r1", chequeId: null },
  ],
}];

describe("LedgerTable", () => {
  it("renders account band, rows, sub total and report total in PACT layout", () => {
    render(<NextIntlClientProvider locale="en" messages={en}><LedgerTable ledgers={ledgers} /></NextIntlClientProvider>);
    expect(screen.getByText(/Account Code :: 166269/)).toBeInTheDocument();
    expect(screen.getByText("TCO-26/15")).toHaveAttribute("href", expect.stringContaining("/dashboard/finance/journals/e1"));
    expect(screen.getAllByText("64,500.00")).toHaveLength(3); // debit cell, balance cell, sub total debit
    expect(screen.getByText("50,800.00 Dr")).toBeInTheDocument();
    expect(screen.getByText("Sub Total")).toBeInTheDocument();
    expect(screen.getByText("Report Total")).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run** → fails.

- [ ] **Step 3: Implement `LedgerTable`, `useNameLookup`, `LedgerFilters`, the three pages**

`LedgerTable.tsx` core (`"use client"`):

```tsx
import Link from "next/link";
import { useLocale, useTranslations } from "next-intl";
import { fmtAmount, fmtBalance, type AccountLedger } from "@/lib/api/ledger";
import { useNameLookup } from "./useNameLookup";

const th = "text-left px-3 py-2 text-[11px] font-semibold text-muted uppercase tracking-wider";
const td = "px-3 py-1.5 text-xs";
const num = `${td} text-right tabular-nums`;

export default function LedgerTable({ ledgers, showTenantColumns = true }: { ledgers: AccountLedger[]; showTenantColumns?: boolean }) {
  const t = useTranslations("Ledger");
  const locale = useLocale();
  const units = useNameLookup("units");
  const renters = useNameLookup("renters");
  const grand = ledgers.reduce((a, l) => ({ dr: a.dr + l.totalDebit, cr: a.cr + l.totalCredit, bal: a.bal + l.closingBalance }), { dr: 0, cr: 0, bal: 0 });
  return (
    <div className="bg-surface border border-border rounded-xl overflow-x-auto">
      <table className="w-full min-w-[900px]">
        <thead><tr className="bg-input/50">
          <th className={th}>{t("docDate")}</th><th className={th}>{t("docNo")}</th><th className={th}>{t("particular")}</th>
          <th className={`${th} text-right`}>{t("debit")}</th><th className={`${th} text-right`}>{t("credit")}</th><th className={`${th} text-right`}>{t("balance")}</th>
          {showTenantColumns && <><th className={th}>{t("unit")}</th><th className={th}>{t("tenant")}</th></>}
          <th className={th}>{t("narration")}</th>
        </tr></thead>
        <tbody>
          {ledgers.map(l => (
            <LedgerBlock key={l.accountId} ledger={l} />
          ))}
          <tr className="bg-warning/10 font-bold">
            <td className={td} colSpan={3}>{t("reportTotal")}</td>
            <td className={num}>{fmtAmount(grand.dr)}</td><td className={num}>{fmtAmount(grand.cr)}</td><td className={num}>{fmtBalance(grand.bal)}</td>
            <td className={td} colSpan={showTenantColumns ? 3 : 1} />
          </tr>
        </tbody>
      </table>
    </div>
  );

  function LedgerBlock({ ledger: l }: { ledger: AccountLedger }) {
    const cols = showTenantColumns ? 9 : 7;
    return (<>
      <tr><td colSpan={cols} className="px-3 py-1.5 text-xs font-bold text-white" style={{ background: "#C8651B" }}>
        Account Code :: {l.accountCode} &nbsp;&nbsp; Name :: {l.accountName}</td></tr>
      {l.openingBalance !== 0 && (
        <tr className="bg-input/30"><td className={td} colSpan={3}>{t("openingBalance")}</td><td className={num} /><td className={num} /><td className={num}>{fmtBalance(l.openingBalance)}</td><td colSpan={cols - 6} /></tr>
      )}
      {l.rows.map(r => (
        <tr key={`${r.entryId}-${r.entryNumber}-${r.balance}`} className="border-t border-border hover:bg-input/30">
          <td className={td}>{new Date(r.entryDate).toLocaleDateString(locale === "ar" ? "ar-AE" : "en-GB")}</td>
          <td className={td}><Link href={`/dashboard/finance/journals/${r.entryId}`} className="text-primary hover:underline">{r.entryNumber}</Link></td>
          <td className={td}>{r.particular}</td>
          <td className={num}>{r.debit ? fmtAmount(r.debit) : ""}</td>
          <td className={num}>{r.credit ? fmtAmount(r.credit) : ""}</td>
          <td className={num}>{fmtBalance(r.balance)}</td>
          {showTenantColumns && <><td className={td}>{units.name(r.unitId)}</td><td className={td}>{renters.name(r.renterId)}</td></>}
          <td className={`${td} text-muted`}>{r.narration}</td>
        </tr>
      ))}
      {l.truncated && <tr><td colSpan={cols} className={`${td} text-warning`}>{t("truncated", { n: l.rows.length })}</td></tr>}
      <tr className="bg-input/40 font-semibold border-t border-border">
        <td className={td} colSpan={3}>{t("subTotal")}</td>
        <td className={num}>{fmtAmount(l.totalDebit)}</td><td className={num}>{fmtAmount(l.totalCredit)}</td><td className={num}>{fmtBalance(l.closingBalance)}</td>
        <td colSpan={cols - 6} />
      </tr>
    </>);
  }
}
```

`useNameLookup.ts`:

```ts
import { useEffect, useState } from "react";
type Kind = "units" | "renters" | "properties";
const cache: Partial<Record<Kind, Promise<Map<string, string>>>> = {};
function load(kind: Kind): Promise<Map<string, string>> {
  if (!cache[kind]) cache[kind] = fetch(`/api/proxy/v1/${kind}?size=1000`).then(r => r.ok ? r.json() : []).then((data) => {
    const list = Array.isArray(data) ? data : (data.content ?? []);
    return new Map(list.map((x: { id: string; unitNumber?: string; fullName?: string; nameEn?: string; name?: string }) =>
      [x.id, x.unitNumber ?? x.fullName ?? x.nameEn ?? x.name ?? x.id]));
  });
  return cache[kind]!;
}
export function useNameLookup(kind: Kind) {
  const [map, setMap] = useState<Map<string, string>>(new Map());
  useEffect(() => { load(kind).then(setMap).catch(() => {}); }, [kind]);
  return { name: (id: string | null) => (id ? map.get(id) ?? "" : "") };
}
```

Confirm the real list endpoints and their name fields (`/api/v1/units`, `/api/v1/renters`, `/api/v1/properties`) by reading their controllers; adjust the field fallbacks.

`LedgerFilters.tsx`: a horizontal bar with two `<input type="date">`, a property `<select>` (from `useNameLookup("properties")` — expose the map too), an optional account chip list using `AccountPicker`, an optional renter `<select>`, and an **Apply** button. Keep state in the page; the component is controlled.

Pages follow the vendors page skeleton (header with icon + title + description, `LoadErrorBanner`, skeleton while loading, empty state with `t("noRows")`). The Trial Balance page groups `TrialBalanceRow[]` by `accountType` in `["ASSET","LIABILITY","INCOME","EXPENSE","EQUITY"]` order, showing `debit`/`credit` columns and a per-type subtotal; grand totals in the footer; if `Math.abs(Σdebit − Σcredit) ≥ 0.005` render a `bg-danger/10` banner "Trial balance does not balance".

Vendors page: add a **Ledger** icon button per row → `router.push(`/dashboard/finance/general-ledger?vendorId=${vendor.id}`)`. Remove the old `payableAccount` column's manual editing if present (the account is system-managed now); keep it read-only.

- [ ] **Step 4: Tests, lint, commit**

`npx vitest run src/components/finance && npx tsc --noEmit && npm run lint` → PASS.

```bash
git add web/src
git commit -m "feat(web): general ledger, tenant ledger and trial balance pages in PACT layout

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 14: Web — Journal vouchers (list, detail, reverse, new JV), fiscal settings page, sidebar

**Files:**
- Create: `web/src/app/[locale]/dashboard/finance/journals/page.tsx`, `journals/[id]/page.tsx`, `journals/new/page.tsx`
- Create: `web/src/app/[locale]/dashboard/settings/fiscal/page.tsx`
- Modify: `web/src/components/ui/MvpSidebar.tsx`
- Test: `web/src/app/[locale]/dashboard/finance/journals/__tests__/new-journal.test.tsx`

**Interfaces:**
- Journals list: table Doc No | Doc Date | Type | Narration | Property | Total | Status; filters Type (`ledgerApi.journals.docTypes()`), From, To, Property; server-side `Pagination` bound to `Page<JournalEntry>` (`totalElements`, `number`, `size`); row click → detail. **New Journal Voucher** button when `canPostJournals`.
- Journal detail: header card (Doc No, Date, Type, Status badge, Narration, Property, source link when `sourceType === "LEASE"` → `/dashboard/leases/{sourceId}`), lines table Line | Account (code — name) | Debit | Credit | Narration | Unit | Tenant, totals row. **Reverse** button (when `status === "POSTED"` and `reversalOfId === null` and `canPostJournals`) opens `ConfirmDialog` with date input (default today) + reason; on success navigates to the reversal's detail. Links `reversalOf` / `reversedBy` to the related entry.
- New JV: form with Date (default today), Narration, Property (optional), a lines grid (Account via `AccountPicker`, Debit, Credit, Narration; *Add line* / *Remove*; starts with 2 rows), a live footer Total debit / Total credit / difference, Post button disabled until balanced and every line has an account and exactly one side > 0. On success: toast `journalPosted` and navigate to detail. Backend `{message}` shown inline on 400.
- Fiscal settings page: card with Fiscal year start month `<select>` 1–12, Books start date, Books locked through (read-only) + **Lock through** date input + button with `ConfirmDialog` (`lockWarning`). Gated by `canManageAccountSetup`.
- Sidebar `financeItems` becomes: Chart of Accounts, Journal Vouchers, General Ledger, Tenant Ledger, Trial Balance, Payments (kept), Vendors, Bank Accounts. `settingsItems` gains Property account template (`/dashboard/settings/account-template`) and Fiscal year & period lock (`/dashboard/settings/fiscal`) behind `canManageAccountSetup`; remove the Account Mappings entry. The `transactions` and `reports` links are removed (pages deleted in Task 15).

- [ ] **Step 1: Failing test for the JV form's balance gate**

```tsx
// journals/__tests__/new-journal.test.tsx
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import NewJournalPage from "../new/page";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "ACCOUNTANT" } } }) }));
vi.mock("@/lib/api/ledger", async (orig) => {
  const m = await orig<typeof import("@/lib/api/ledger")>();
  return { ...m, ledgerApi: { ...m.ledgerApi,
    accounts: { list: vi.fn(async () => [
      { id: "b", code: "100001", name: "ENBD Main", accountType: "ASSET", group: false, active: true },
      { id: "c", code: "F-01", name: "Capital Account", accountType: "EQUITY", group: false, active: true } ]) },
    journals: { postManual: vi.fn(async () => ({ id: "j1", entryNumber: "JV-26/1", lines: [] })) } } };
});

describe("New journal voucher", () => {
  it("keeps Post disabled until debits equal credits", async () => {
    render(<NextIntlClientProvider locale="en" messages={en}><NewJournalPage /></NextIntlClientProvider>);
    const post = await screen.findByRole("button", { name: "Post" });
    expect(post).toBeDisabled();
    const debits = screen.getAllByLabelText("Debit");
    const credits = screen.getAllByLabelText("Credit");
    fireEvent.change(debits[0], { target: { value: "5000" } });
    fireEvent.change(credits[1], { target: { value: "4000" } });
    expect(screen.getByText("Debits and credits must be equal")).toBeInTheDocument();
    fireEvent.change(credits[1], { target: { value: "5000" } });
    await waitFor(() => expect(screen.queryByText("Debits and credits must be equal")).not.toBeInTheDocument());
    // still disabled: accounts not chosen
    expect(post).toBeDisabled();
  });
});
```

Give the debit/credit inputs `aria-label={t("debit")}` / `aria-label={t("credit")}` so the test can find them.

- [ ] **Step 2: Run** → fails.

- [ ] **Step 3: Implement the three journal pages + fiscal page + sidebar** per the Interfaces block. Reuse `Pagination`, `ConfirmDialog`, `LoadErrorBanner`, `AccountPicker`. Status badge classes: POSTED → `bg-success/10 text-success border-success/20`, REVERSED → `bg-input text-muted border-border`.

Balance logic for the JV form:

```ts
const totals = lines.reduce((a, l) => ({ dr: a.dr + (Number(l.debit) || 0), cr: a.cr + (Number(l.credit) || 0) }), { dr: 0, cr: 0 });
const balanced = Math.abs(totals.dr - totals.cr) < 0.005 && totals.dr > 0;
const complete = lines.every(l => l.accountId && ((Number(l.debit) > 0) !== (Number(l.credit) > 0)));
const canPost = balanced && complete && lines.length >= 2 && !submitting;
```

- [ ] **Step 4: Tests, lint, commit**

`npx vitest run "src/app/\[locale\]/dashboard/finance/journals" && npx tsc --noEmit && npm run lint` → PASS.

```bash
git add web/src
git commit -m "feat(web): journal vouchers (list, detail, reverse, manual JV), fiscal settings, finance sidebar

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 15: Remove v1 finance — changeset 82, delete services/entities/pages, strip callers

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/82-drop-v1-finance.yaml`; append include to master
- Delete (backend): `domain/entity/FinancialTransaction.java`, `domain/entity/AccountMapping.java`, `domain/entity/enums/TransactionNature.java`, `domain/repository/FinancialTransactionRepository.java`, `domain/repository/AccountMappingRepository.java`, `core/service/FinancialTransactionService.java`, `core/service/AccountMappingService.java`, `api/FinancialTransactionController.java`, `api/AccountMappingController.java`, `api/dto/AccountMappingDTO.java`, `api/dto/SaveAccountMappingDTO.java`, `api/dto/{ReportDTO,TrialBalanceDTO,VatReturnDTO,PortfolioProfitLossDTO}.java` (only if nothing else imports them — grep first), their tests (`FinancialTransactionQueryCountIT`, `ChartOfAccountsFallbackCodesTest`, `AccountMapping*Test`, `FinancialTransaction*Test`)
- Modify (backend): `PaymentScheduleService`, `OnlinePaymentService`, `SettlementService`, `PenaltyPaymentService`, `PenaltyService`, `AccountDeletionService`, `DashboardService` (if it reads `financial_transactions`), `VendorService` (if it exposes a vendor ledger via FT)
- Delete (web): `finance/transactions/page.tsx` (+ `__tests__`), `finance/reports/page.tsx`, `settings/account-mappings/page.tsx`, `e2e/finance/transactions.spec.ts`, `e2e/finance/reports.spec.ts`; edit `e2e/finance/accounts.spec.ts`, `e2e/rbac/route-access-control.spec.ts`, `e2e-prod/tests/13-finance-and-settings.spec.ts`, `e2e-prod/tests/04-reports.spec.ts`, `e2e-prod/tutorial-coverage.json` to drop the removed routes
- Test: `backend/src/test/java/com/datagami/rentaxis/domain/V1FinanceRemovedIT.java`

**Interfaces:**
- Produces: a backend with **no** reference to `FinancialTransaction`, `AccountMapping`, `TransactionNature`; cheque/settlement/penalty/online-payment services keep their status transitions but post nothing (Plans 2–3 rewire them to `PostingService`). Each stripped call site gets a one-line comment `// Ledger posting moves to PostingService in accounting v2 plan 2/3 (see spec §7/§9).`

- [ ] **Step 1: Failing test**

```java
@SpringBootTest
@Testcontainers
class V1FinanceRemovedIT {
    @Container @ServiceConnection static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired JdbcTemplate jdbc;

    @Test
    void v1FinanceTablesAreGone() {
        Integer n = jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_name in ('financial_transactions','account_mappings')", Integer.class);
        assertThat(n).isZero();
    }
}
```

- [ ] **Step 2: Changeset 82**

```yaml
databaseChangeLog:
  # Accounting v2 plan 1, final step: the one-legged v1 ledger and the
  # nature-based mapping table are replaced by journal_entries/journal_lines
  # (81) and role mappings. Wipe-and-reseed was agreed (spec D4) — there is
  # no data migration. transaction_splits live inside financial_transactions
  # (parent_transaction_id / is_split_parent) and go with it.
  - changeSet:
      id: 82-drop-v1-finance
      author: claude
      changes:
        - sql:
            sql: |
              ALTER TABLE IF EXISTS penalty_payments DROP COLUMN IF EXISTS financial_transaction_id;
              DROP TABLE IF EXISTS financial_transactions CASCADE;
              DROP TABLE IF EXISTS account_mappings CASCADE;
      rollback:
        - sql:
            sql: SELECT 1  -- no rollback: v1 finance is retired
```

Before writing it, grep the changesets for every column that references `financial_transactions` (`financial_transaction_id` on `penalty_payments` from `35-…`; check `settlement_*`, `vendor_*`) and drop each in the same `sql` block.

- [ ] **Step 3: Delete the Java, strip the callers**

For each caller, delete the field, constructor parameter and every call; keep the status mutation and the `PaymentSchedule`/`Penalty`/`Settlement` saves:
- `PaymentScheduleService.clearPayment` (the block that posted Dr bank / Cr income), `recordChequeBounce` call in `bouncePayment`/`markFailed`
- `OnlinePaymentService.clearPaymentFromWebhook` / `verifyPayment` posting block
- `SettlementService.postDepositSettlement` (delete the method; `finalizeSettlement` stops calling it)
- `PenaltyPaymentService` → `recordPenaltyIncome` call and the `financialTransactionId` field on `PenaltyPayment`
- `AccountDeletionService`: replace "account has transactions" check with `journalLineRepository.existsByAccount_Id(id) || propertyAccountMappingRepository.existsByAccount_Id(id)` → `BusinessRuleViolationException("Account has posted journal lines or mappings")`
- `DashboardService`: if any card summed `financial_transactions`, compute it from `JournalLineRepository.balancesAsOf` for the role's account(s) via `AccountResolver` **or**, if the card is cash-collected-this-month, from `PaymentSchedule` CLEARED rows (already the source for monthly collections). Choose the source that does not need the ledger; note it in a comment.

Compile: `cd backend && ./gradlew compileJava compileTestJava`. Fix every `cannot find symbol` until clean.

- [ ] **Step 4: Web deletions + sidebar/e2e edits**, then `cd web && npx tsc --noEmit && npm run lint && npx vitest run`.

- [ ] **Step 5: Full backend suite, commit**

`cd backend && ./gradlew test` → PASS (pay attention to `PaymentSchedule*`, `Settlement*`, `OnlinePayment*`, `Penalty*` tests: any that asserted a `FinancialTransaction` was written are deleted or reduced to asserting the status transition; note each such test in the commit body).

```bash
git add -A backend web
git commit -m "refactor(finance): retire v1 financial_transactions and account_mappings

Changeset 82 drops the tables. Cheque, settlement, penalty and online-payment
services keep their state machines and stop posting; Plans 2-3 rewire them
to PostingService. Transactions, reports and account-mappings pages removed.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 16: End-to-end check, demo seed smoke, PR

**Files:**
- Modify: `scripts/seed_demo_tenant.py` — after `POST /api/v1/finance/accounts/seed`, no change is needed (the seed endpoint now also seeds template + defaults, Task 6); remove any step that called `/finance/account-mappings` or `/finance/transactions`; the cheque-lifecycle steps still work (status only)
- Create: `web/e2e/finance/ledger.spec.ts`
- Modify: `docs/superpowers/plans/…` (tick boxes), `CLAUDE.md` line "next: `37-*.yaml`" → "next: `83-*.yaml`"

- [ ] **Step 1: Playwright smoke** (`web/e2e/finance/ledger.spec.ts`, dev suite, logged in as TENANT_ADMIN via the existing helpers used by `accounts.spec.ts`):
  1. Seed CoA from the Chart of Accounts page if empty.
  2. Create a property "E2E Tower"; open it → Accounts tab → expect a row `RENT_RECEIVABLE` with account `Rent Receivable - E2E Tower`.
  3. Journals → New: date today, narration "E2E capital", line 1 account `Emirates Islamic - E2E Tower` debit 1000, line 2 `F-01 Capital Account` credit 1000 → Post → detail shows `JV-` number and 2 lines.
  4. General Ledger: filter account `Emirates Islamic - E2E Tower` → one row, balance `1,000.00 Dr`.
  5. Trial Balance: as of today → totals equal, no red banner.
  6. Detail → Reverse (reason "e2e") → original shows Reversed; ledger closing balance `0.00`.

- [ ] **Step 2: Run everything**

```bash
cd backend && ./gradlew test
cd ../web && npx tsc --noEmit && npm run lint && npx vitest run && npx playwright test e2e/finance
```
Expected: all green. Record the counts from the runners in the PR body (counts from the runner, not grep).

- [ ] **Step 3: Demo seed** — run `python3 scripts/seed_demo_tenant.py` against a local stack (`docker compose up -d`) and confirm it completes; fix any call to a removed endpoint.

- [ ] **Step 4: Commit, push, open PR**

```bash
git add -A
git commit -m "test(ledger): e2e smoke for property accounts, manual JV, ledgers and reversal

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
git push -u origin feat/accounting-v2-ledger-core
gh pr create --base main --title "feat(ledger): accounting v2 plan 1 — ledger core" --body-file <(cat <<'EOF'
## Summary
Plan 1 of the accounting v2 spec (`docs/superpowers/specs/2026-09-17-accounting-v2-design.md`): immutable balanced journal, role-resolved per-property account sets, fiscal period lock, manual JV, and the four control views. Retires v1 `financial_transactions`/`account_mappings` (wipe-and-reseed agreed).

## What changed
- Changesets 81 (journal, mappings, template, fiscal) and 82 (drop v1)
- `PostingService` / `AccountResolver` / `PropertyAccountService` / `LedgerQueryService` / `JournalService`
- Web: CoA on parentId, property Accounts tab, GL / Tenant Ledger / Trial Balance, Journal Vouchers, settings for template + fiscal
- `ACCOUNTANT` role

## Not in this PR (Plans 2–5)
Lease posting, cheque entity and lifecycle journals, per-day recognition, settlement/termination, vouchers, cut-over import, mobile.

## Verification
- backend: `./gradlew test` → <n> tests, 0 failures
- web: vitest <n> passed; playwright e2e/finance <n> passed

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)
```

---

## Self-review

**Spec coverage (plan 1 scope = §4, §5, §11 control views, §4.7 removals):**
- §4.1 Account (parent_id, property_id, alias, no stored balance) — Tasks 1–2 ✔
- §4.2 JournalEntry/Line, one-side CHECK, immutability, balance trigger, period-lock exemption for OB/import — Tasks 1, 3, 5 ✔
- §4.3 PostingService `post`/`reverse`, roles not codes — Task 5 ✔
- §4.4 AccountResolver property → default → fail — Task 4 ✔
- §4.5 fiscal settings, entry numbering by fiscal year — Task 3, Task 10 ✔
- §4.6 Manual JV — Task 9 (API), Task 14 (UI) ✔
- §4.7 removals — Task 15 ✔
- §5.1 AccountRole — Task 4 ✔ · §5.2 two mapping tables — Tasks 1, 4, 6 ✔ · §5.3 template seeded from PACT conventions — Task 6 ✔ · §5.4 generate on create / generate-missing / Accounts tab / bank default — Tasks 6, 7, 12 ✔ · §5.5 vendors silent leaf, renters never accounts — Task 7 ✔
- §11 Finance screens in scope: CoA, Property Accounts tab, Settings template/fiscal, GL, Tenant Ledger, Vendor Ledger, Trial Balance, Journal Vouchers + New JV — Tasks 12–14 ✔; RBAC `ACCOUNTANT` — Tasks 2, 11 ✔
- §5.4 posting-time guard (`resolveAll` listing unmapped roles) — Task 4 provides it; the guard itself is invoked by lease posting in Plan 2.
- Not in plan 1 by design: charge types, cheques, recognition, vouchers, OB screen/import (Plans 2–4).

**Placeholders:** none of "TBD/TODO/similar to Task N". Two places tell the engineer to *confirm* a real name before using it (`Emirate` constant, `Renter`/`User` required fields, `VendorService`/`BankAccountService` method names, property detail page path, list endpoints for the name lookup) — those are lookups against the repo, with the fallback stated, not gaps.

**Type consistency:** `PostingRequest.Dimensions/Line/dr/cr` used identically in Tasks 5, 8, 9; `LedgerFilter` is the nested record in `LedgerQueryService` in both Task 8's test and controller; `RoleMappingDTO(role, accountId, accountCode, accountName, inherited)` matches Task 6 service, Task 11 type and Task 12 component; `AccountLedgerDTO` field names match `AccountLedger` in `ledger.ts` and `LedgerTable`; `JournalEntryDTO.total` is used by the Task 9 test and the Task 14 list page; `TenantFiscalSettingsService.get/fiscalYearOf/assertOpen/lockThrough/setBooksStartDate/setFiscalYearStartMonth` match Tasks 3, 5, 10.
