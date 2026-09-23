# Lease Addendum (mid-term Add charge) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a finance user add a charge (parking bay, storage, an extra chiller line) to a posted, ACTIVE lease mid-term, even after cheques have cleared, as a numbered addendum (`ADD-yy/n`) that posts its own TCO and registers the cheques that pay for it. This fixes findings #15 and #17.

**Architecture:** Approach A from the spec: a new `LeaseVariationService.addCharge` reaches the additive path that `LeaseRenewalService.extend` already uses (`appendLines` → `appendRows` → `postTco` → `chequeRegistrar.register`), with the charge window pinned to `[effectiveFrom, lease.endDate]` instead of an extension window. The window/value/Σ-cheques logic moves out of `LeaseRenewalService` into a package-private `AdditionalCharges` component shared by both callers. Nothing else in `extend` changes. Recognition gets the new RENT lines through the existing `appendForExtension` via a new `LeaseVariedEvent`.

**Tech Stack:** Java 21, Spring Boot 4.0.3, Spring Data JPA, Liquibase, PostgreSQL 16 (Testcontainers via `AbstractPostgresIT`), Next.js 16 + TypeScript + next-intl, Vitest.

**Spec:** `docs/superpowers/specs/2026-09-23-lease-addendum-design.md` (approved 2026-09-23).

## Global Constraints

- The golden ledger gate stays green after every backend task: `cd backend && ./gradlew test -PincludeTags=golden`.
- Liquibase changesets are append-only. This plan's changeset is **`91-lease-addenda.yaml`**.
- Every tenant-scoped read or write runs inside `@Transactional` (`TenantAspect` only enables the tenant filter inside a transaction). New entities extend `BaseTenantEntity`.
- The addendum's journal is a **TCO** (the PACT vocabulary is unchanged). `ADD-yy/n` is the *document* number on the addendum record, drawn from the same per-tenant/per-year counter table as journal numbers, under series `"ADD"`.
- Ejari: optional at creation. A blank Ejari marks the addendum **Ejari pending** and can be filled in later.
- A DEPOSIT line is refused on an addendum, exactly as on an extension.
- Posting roles only: `SUPER_ADMIN`, `TENANT_ADMIN`, `ACCOUNTANT` (same list as `/extend`; web permission `canExtendLeases`).
- Web: never JSON round-trip `web/messages/{en,ar}.json`. Insert keys by line, then check that both files parse. Always update both locales.
- Commits: conventional (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`), ending with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- zsh: capture exit codes explicitly (`./gradlew … ; rc=$?`). Never trust the exit code of a pipeline ending in `| tail`.

## Deviations from the spec (read before starting)

1. **No `correct()` for settled leases in this plan.** The spec says the settled correction is "the same additive document with a different sign". That is false in the current code for a *downward* delta:
   - `LeasePostingService.planLines` emits pairs only for `lineNet.signum() > 0`.
   - `RecognitionService.build` skips `net.signum() <= 0` (`RecognitionService.java:848`).
   - The database forbids a negative line: `ck_lease_lines_net … net_amount >= 0` (`83-lease-posting-and-cheques.yaml:83`).

   A downward correction after money has cleared needs a credit note (Dr `ADVANCE_RENT`/income, Cr `RENT_RECEIVABLE`) plus re-planning of future recognition entries. That is new design, recorded as a spec amendment in Task 6. The approved "credit balance → `ADVANCE_RENT`" answer needs revisiting with it: `ADVANCE_RENT` is the unearned-rent liability every TCO already credits, not a per-renter credit account.

   What ships now:
   - An **upward** correction on a settled lease is an addendum (this plan).
   - An unsettled correction is today's `amendLines` (unchanged).
   - The amend dialog's re-price message stops pointing at the impossible "change the cheque grid first" and points at **Add charge** instead (Task 5).
2. **No general `addRowToPostedLease` endpoint.** An addendum registers its own cheques. A free-standing row with no charge behind it would make Σ cheques ≠ contract value, which every other path forbids. The `cash-receipt` wrapper stays the only direct caller.
3. **Endpoint name** `/api/v1/leases/{id}/addenda` rather than `/variations`, because the resource created is an addendum with its own number.

---

## File structure

**Backend: create**
- `backend/src/main/resources/db/changelog/changesets/91-lease-addenda.yaml`: `lease_addenda` table and a nullable `lease_lines.addendum_id`.
- `backend/src/main/java/com/datagami/rentaxis/domain/entity/LeaseAddendum.java`: the addendum record.
- `backend/src/main/java/com/datagami/rentaxis/domain/repository/LeaseAddendumRepository.java`
- `backend/src/main/java/com/datagami/rentaxis/core/service/lease/AdditionalCharges.java`: window pinning, value, Σ-cheques check (extracted from `LeaseRenewalService`).
- `backend/src/main/java/com/datagami/rentaxis/core/service/lease/LeaseVariationService.java`: `addCharge`, `list`, `recordEjari`.
- `backend/src/main/java/com/datagami/rentaxis/core/service/lease/LeaseVariedEvent.java`
- `backend/src/main/java/com/datagami/rentaxis/api/dto/lease/AddChargeRequest.java`
- `backend/src/main/java/com/datagami/rentaxis/api/dto/lease/RecordEjariRequest.java`
- `backend/src/main/java/com/datagami/rentaxis/api/dto/lease/LeaseAddendumDTO.java`
- `backend/src/main/java/com/datagami/rentaxis/api/dto/lease/AddendumResponse.java`
- `backend/src/test/java/com/datagami/rentaxis/core/service/lease/LeaseVariationServiceIT.java`
- `backend/src/test/java/com/datagami/rentaxis/api/LeaseControllerAddendumEndpointsIT.java`

**Backend: modify**
- `backend/src/main/resources/db/changelog/db.changelog-master.yaml`: include 91.
- `backend/src/main/java/com/datagami/rentaxis/domain/entity/LeaseLine.java`: `addendumId`.
- `backend/src/main/java/com/datagami/rentaxis/core/service/ledger/EntryNumberService.java`: `nextDocumentNumber(String, LocalDate)`.
- `backend/src/main/java/com/datagami/rentaxis/core/service/lease/LeaseRenewalService.java`: delegate to `AdditionalCharges`.
- `backend/src/main/java/com/datagami/rentaxis/core/service/recognition/RecognitionEventListener.java`: `onLeaseVaried`.
- `backend/src/main/java/com/datagami/rentaxis/api/LeaseController.java`: three endpoints.

**Web: create**
- `web/src/components/cheques/ChequeRowsEditor.tsx`: the cheque grid extracted from `ExtendLeaseDialog`.
- `web/src/components/leases/AddChargeDialog.tsx`
- `web/src/components/leases/LeaseAddendaPanel.tsx`
- `web/src/components/leases/__tests__/AddChargeDialog.test.tsx`
- `web/src/components/leases/__tests__/LeaseAddendaPanel.test.tsx`

**Web: modify**
- `web/src/lib/api/leasing.ts`: types and three client calls.
- `web/src/components/leases/ExtendLeaseDialog.tsx`: use `ChequeRowsEditor`.
- `web/src/app/[locale]/dashboard/leases/[id]/page.tsx`: Add charge button, dialog, addenda panel.
- `web/messages/en.json`, `web/messages/ar.json`

**Docs: modify**
- `tutorials/miftah-2y/gaps.md`, `tutorials/miftah-2y/FIXPLAN.md`, `docs/superpowers/specs/2026-09-23-lease-addendum-design.md`

---

### Task 1: Extract `AdditionalCharges` from `LeaseRenewalService` (pure refactor)

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/lease/AdditionalCharges.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/lease/LeaseRenewalService.java` (fields/constructor lines 77-105; `extend` lines 286-298; delete `datedLines` 348-396 and `extensionValue` 398-421)
- Test (regression only): `backend/src/test/java/com/datagami/rentaxis/core/service/lease/LeaseRenewalServiceIT.java`, golden gate

**Interfaces:**
- Produces:
  - `AdditionalCharges.Act` enum: `EXTENSION`, `ADDENDUM`.
  - `List<LeaseLineInput> dated(List<LeaseLineInput> inputs, LocalDate windowStart, LocalDate windowEnd, Act act)`
  - `BigDecimal valueOf(List<LeaseLineInput> dated)`
  - `void requireCovered(List<ChequeRowInput> rows, BigDecimal charged, Act act)`

  All are package-private, in package `com.datagami.rentaxis.core.service.lease`.

- [ ] **Step 1: Record the baseline**

Run: `cd backend && ./gradlew test --tests '*LeaseRenewalServiceIT' ; rc=$? ; echo rc=$rc`
Expected: `rc=0`. Every existing extend assertion is the regression net for this refactor, including the exact messages `"Cheque rows total 11,000.00 but the extension charges 12,000.00"`, `"an extension cannot charge a deposit"` and `"a rent line must cover part of the extension ("`.

- [ ] **Step 2: Create `AdditionalCharges`**

```java
package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.domain.entity.ChargeType;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Charges added to a lease that is already on the books: an extension's extra
 * term (spec §6.7) and an addendum's mid-term charge. Both are additive: new
 * lines inside a window, a further TCO for them, and cheques that pay for
 * exactly that.
 *
 * <p>The window is what differs. An extension's starts the day after the old end
 * date; an addendum's runs from its effective date to the lease's current end.
 * Everything else — the deposit refusal, the rent-window rule, the VAT-inclusive
 * value and the Σ-cheques check — is the same rule and lives here once.</p>
 */
@Component
class AdditionalCharges {

    /** Which act is charging, for the wording of a refusal. */
    enum Act {
        EXTENSION("an extension", "the extension"),
        ADDENDUM("an addendum", "the addendum");

        final String indefinite;
        final String definite;

        Act(String indefinite, String definite) {
            this.indefinite = indefinite;
            this.definite = definite;
        }
    }

    private final LeaseService leaseService;

    AdditionalCharges(LeaseService leaseService) {
        this.leaseService = leaseService;
    }

    /**
     * The lines with their window pinned on.
     *
     * <p>A RENT line covers the window and nothing else. Leaving it to
     * {@code LeaseService}'s defaults would give it the lease's <em>whole</em>
     * term, which per-day recognition would then charge from the original start
     * date — every month of the original term recognised a second time. A caller
     * may name a narrower window inside this one, but never one that reaches
     * outside it.</p>
     *
     * <p>A DEPOSIT line is refused outright. The deposit is held for the tenancy
     * and the tenancy has not changed; a renter who genuinely owes more deposit
     * is charged it as a separate act.</p>
     */
    List<LeaseLineInput> dated(List<LeaseLineInput> inputs, LocalDate windowStart, LocalDate windowEnd, Act act) {
        if (inputs == null || inputs.isEmpty()) {
            throw new BusinessRuleViolationException("At least one line is required");
        }
        List<LeaseLineInput> out = new ArrayList<>(inputs.size());
        int seqNo = 0;
        for (LeaseLineInput in : inputs) {
            seqNo++;
            if (in == null) {
                throw new BusinessRuleViolationException("Line " + seqNo + " is empty");
            }
            ChargeType type = leaseService.chargeTypeOf(in, seqNo);
            String where = "Line " + seqNo + " (" + type.getCode() + ")";
            if (type.getBehaviour() == ChargeBehaviour.DEPOSIT) {
                throw new BusinessRuleViolationException(where + ": " + act.indefinite
                        + " cannot charge a deposit — the tenancy's deposit is already held.");
            }
            if (type.getBehaviour() != ChargeBehaviour.RENT) {
                // A fee carries no period; it is charged for the act itself.
                out.add(in);
                continue;
            }
            LocalDate from = in.periodStart() != null ? in.periodStart() : windowStart;
            LocalDate to = in.periodEnd() != null ? in.periodEnd() : windowEnd;
            if (from.isBefore(windowStart) || to.isAfter(windowEnd) || to.isBefore(from)) {
                throw new BusinessRuleViolationException(where + ": a rent line must cover part of "
                        + act.definite + " (" + windowStart + " to " + windowEnd + "), not " + from + " to " + to + ".");
            }
            out.add(new LeaseLineInput(in.chargeTypeId(), in.chargeTypeCode(), in.grossAmount(),
                    in.discountAmount(), in.narration(), in.vatApplicable(), in.creditAccountId(), from, to));
        }
        return out;
    }

    /**
     * What the lines charge, VAT included — the figure Σ cheques must equal.
     * Computed through {@link LeaseVat} on transient lines, the same object the
     * post's own guard measures, so the two cannot round differently.
     */
    BigDecimal valueOf(List<LeaseLineInput> dated) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < dated.size(); i++) {
            LeaseLineInput in = dated.get(i);
            ChargeType type = leaseService.chargeTypeOf(in, i + 1);
            BigDecimal gross = in.grossAmount() == null ? BigDecimal.ZERO : in.grossAmount();
            BigDecimal discount = in.discountAmount() == null ? BigDecimal.ZERO : in.discountAmount();

            LeaseLine probe = new LeaseLine();
            probe.setChargeType(type);
            probe.setNetAmount(gross.subtract(discount));
            probe.setVatApplicable(in.vatApplicable() != null ? in.vatApplicable() : type.isVatApplicableDefault());
            total = total.add(LeaseVat.grossOf(probe));
        }
        return total;
    }

    /** Σ rows must equal what the lines charge; refused before anything is written. */
    void requireCovered(List<ChequeRowInput> rows, BigDecimal charged, Act act) {
        BigDecimal collected = BigDecimal.ZERO;
        for (ChequeRowInput row : rows) {
            if (row != null && row.amount() != null) collected = collected.add(row.amount());
        }
        if (collected.compareTo(charged) != 0) {
            throw new BusinessRuleViolationException("Cheque rows total " + LeasePostingService.money(collected)
                    + " but " + act.definite + " charges " + LeasePostingService.money(charged) + ".");
        }
    }
}
```

- [ ] **Step 3: Point `extend` at it**

In `LeaseRenewalService`:
- Add the field `private final AdditionalCharges charges;` and a constructor parameter `AdditionalCharges charges` (last position), assigned in the body.
- Replace lines 286-298 (from `List<LeaseLineInput> inputs = datedLines(...)` through the Σ-mismatch `throw`) with:

```java
        List<LeaseLineInput> inputs = charges.dated(r.lines(), windowStart, r.newEndDate(),
                AdditionalCharges.Act.EXTENSION);
        BigDecimal charged = charges.valueOf(inputs);

        List<ChequeRowInput> rows = r.cheques() == null ? List.of() : r.cheques();
        charges.requireCovered(rows, charged, AdditionalCharges.Act.EXTENSION);
```

- Delete the private methods `datedLines` and `extensionValue`, and their javadoc. Their text now lives on `AdditionalCharges`.
- Remove imports that are now unused (`ChargeBehaviour`, `ChargeType` if unused). Compile to confirm.

- [ ] **Step 4: Run the regression net and the golden gate**

Run: `cd backend && ./gradlew test --tests '*LeaseRenewalServiceIT' ; rc=$? ; echo rc=$rc`
Expected: `rc=0`, with every extend message unchanged.

Run: `cd backend && ./gradlew test -PincludeTags=golden ; rc=$? ; echo rc=$rc`
Expected: `rc=0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/lease/AdditionalCharges.java \
        backend/src/main/java/com/datagami/rentaxis/core/service/lease/LeaseRenewalService.java
git commit -m "refactor: move extension charge-window rules into AdditionalCharges

So an addendum can reuse the same window, VAT and cheque-cover rules
without a second copy. No behaviour change; extend's messages are
identical.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Schema, entity and `ADD-yy/n` numbering

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/91-lease-addenda.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (after the 90 include at line 189)
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/LeaseAddendum.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/LeaseAddendumRepository.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/LeaseLine.java` (after `periodEnd`, line 82)
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/ledger/EntryNumberService.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/lease/LeaseVariationServiceIT.java` (numbering is asserted there in Task 3; this task is proven by the app context starting and Liquibase applying 91)

**Interfaces:**
- Produces:
  - `LeaseAddendum` with getters/setters: `id`, `lease`, `addendumNumber`, `effectiveFrom`, `contractDate`, `ejariNumber`, `reason`, `value`, `tcoJournalId`, `tcoEntryNumber`, `createdAt`, `createdBy`.
  - `LeaseAddendumRepository.findByLease_IdOrderByCreatedAtAsc(UUID)` and `findByIdAndLease_Id(UUID, UUID)`.
  - `LeaseLine.getAddendumId()` / `setAddendumId(UUID)`.
  - `EntryNumberService.nextDocumentNumber(String series, LocalDate date)` returns e.g. `"ADD-27/1"`.

- [ ] **Step 1: Write the changeset**

`91-lease-addenda.yaml`:

```yaml
databaseChangeLog:
  # A mid-term charge on a posted lease (findings #15/#17): a parking bay sold in
  # February, a storage room, an extra chiller line. The ledger already supports
  # it additively — new lines, a further TCO, the cheques that pay for it — and
  # this is the document that says so: its own ADD-yy/n number, the Ejari it was
  # registered under (blank until re-registration, which is how "Ejari pending"
  # is known), and the TCO it posted.
  #
  # lease_lines.addendum_id ties each line to the addendum that charged it, so the
  # lease page can say which charges came later. Nullable: a contract's own lines
  # and an extension's have none. An amendment rewrites the whole line set and
  # clears it, which is right — the amended lines belong to the reposted contract.
  - changeSet:
      id: 91-lease-addenda
      author: rentaxis-system
      changes:
        - createTable:
            tableName: lease_addenda
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: lease_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_lease_addenda_lease, referencedTableName: leases, referencedColumnNames: id } }
              - column: { name: addendum_number, type: varchar(40), constraints: { nullable: false } }
              - column: { name: effective_from, type: date, constraints: { nullable: false } }
              - column: { name: contract_date, type: date, constraints: { nullable: false } }
              - column: { name: ejari_number, type: varchar(255) }
              - column: { name: reason, type: text }
              - column: { name: value, type: "decimal(14,2)", constraints: { nullable: false } }
              - column: { name: tco_journal_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_lease_addenda_tco, referencedTableName: journal_entries, referencedColumnNames: id } }
              - column: { name: tco_entry_number, type: varchar(40), constraints: { nullable: false } }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
              - column: { name: created_by, type: uuid }
        - addUniqueConstraint:
            tableName: lease_addenda
            columnNames: tenant_id, addendum_number
            constraintName: uq_lease_addenda_number
        - createIndex:
            tableName: lease_addenda
            indexName: ix_lease_addenda_lease
            columns:
              - column: { name: lease_id }
        - addColumn:
            tableName: lease_lines
            columns:
              - column: { name: addendum_id, type: uuid, constraints: { foreignKeyName: fk_lease_lines_addendum, referencedTableName: lease_addenda, referencedColumnNames: id } }
```

Add to `db.changelog-master.yaml` directly after the `90-ticket-reported-date.yaml` include, copying its exact indentation:

```yaml
  - include:
      file: db/changelog/changesets/91-lease-addenda.yaml
```

- [ ] **Step 2: Entity and repository**

`LeaseAddendum.java`:

```java
package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A mid-term charge on a posted lease, as a document: its own {@code ADD-yy/n}
 * number, the window it took effect from, and the {@code TCO} it posted. The
 * lines it charged carry {@code addendum_id}; the cheques that pay for it are
 * ordinary rows on the lease's register.
 *
 * <p>Immutable once written except {@code ejariNumber}, which is blank until the
 * variation is re-registered and is the whole of "Ejari pending".</p>
 */
@Entity
@Table(name = "lease_addenda")
@Getter
@Setter
public class LeaseAddendum extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    @Column(name = "addendum_number", nullable = false, length = 40)
    private String addendumNumber;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "contract_date", nullable = false)
    private LocalDate contractDate;

    @Column(name = "ejari_number")
    private String ejariNumber;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "value", nullable = false, precision = 14, scale = 2)
    private BigDecimal value;

    @Column(name = "tco_journal_id", nullable = false)
    private UUID tcoJournalId;

    @Column(name = "tco_entry_number", nullable = false, length = 40)
    private String tcoEntryNumber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private UUID createdBy;
}
```

`LeaseAddendumRepository.java`:

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseAddendum;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaseAddendumRepository extends JpaRepository<LeaseAddendum, UUID> {
    List<LeaseAddendum> findByLease_IdOrderByCreatedAtAsc(UUID leaseId);

    /** Scoped to the lease in the path, so an addendum id from another lease is a 404, not an edit. */
    Optional<LeaseAddendum> findByIdAndLease_Id(UUID id, UUID leaseId);
}
```

In `LeaseLine.java`, after the `periodEnd` field:

```java
    /** The addendum that charged this line; null for the contract's own lines and an extension's. */
    @Column(name = "addendum_id")
    private UUID addendumId;
```

If `LeaseLine` uses Lombok `@Getter @Setter` at class level (check the top of the file), no accessor code is needed. If it has hand-written accessors, add `getAddendumId()` / `setAddendumId(UUID)` in the same style.

- [ ] **Step 3: `EntryNumberService.nextDocumentNumber`**

Replace the body of `next(JournalDocType, LocalDate)` so both share one counter implementation:

```java
    @Transactional(propagation = Propagation.REQUIRED)
    public String next(JournalDocType docType, LocalDate entryDate) {
        return nextDocumentNumber(docType.name(), entryDate);
    }

    /**
     * The same counter for a document that is not a journal — an addendum's
     * {@code ADD-yy/n}. The sequence table is keyed by a free-text series, so
     * a series that is not a {@link JournalDocType} name cannot collide with one.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextDocumentNumber(String series, LocalDate date) {
        UUID tenantId = TenantContextHolder.getTenantId();
        int fy = fiscal.fiscalYearOf(date);
        JournalEntrySequence seq = repo.lock(tenantId, series, fy).orElseGet(() -> create(tenantId, series, fy));
        long value = seq.getNextValue();
        seq.setNextValue(value + 1);
        repo.save(seq);
        return series + "-" + String.format(Locale.ROOT, "%02d", fy % 100) + "/" + value;
    }
```

Change `create` to take `String series` instead of `JournalDocType docType`, passing `series` where it passed `docType.name()`.

- [ ] **Step 4: Compile, and prove the migration and the unchanged numbering**

Run: `cd backend && ./gradlew compileJava compileTestJava ; rc=$? ; echo rc=$rc`
Expected: `rc=0`.

Run: `cd backend && ./gradlew test --tests '*LeaseRenewalServiceIT' ; rc=$? ; echo rc=$rc`
Expected: `rc=0`. The context starts, Liquibase applies 91, and TCO numbering through `next()` is unchanged.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/changelog/changesets/91-lease-addenda.yaml \
        backend/src/main/resources/db/changelog/db.changelog-master.yaml \
        backend/src/main/java/com/datagami/rentaxis/domain/entity/LeaseAddendum.java \
        backend/src/main/java/com/datagami/rentaxis/domain/repository/LeaseAddendumRepository.java \
        backend/src/main/java/com/datagami/rentaxis/domain/entity/LeaseLine.java \
        backend/src/main/java/com/datagami/rentaxis/core/service/ledger/EntryNumberService.java
git commit -m "feat: add lease_addenda table and ADD-yy/n numbering

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: `LeaseVariationService.addCharge` (the #15/#17 capability)

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/lease/AddChargeRequest.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/lease/LeaseAddendumDTO.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/lease/AddendumResponse.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/lease/LeaseVariedEvent.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/lease/LeaseVariationService.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/recognition/RecognitionEventListener.java` (after `onLeaseExtended`, line 55)
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/lease/LeaseVariationServiceIT.java`

**Interfaces:**
- Consumes (Task 1): `AdditionalCharges.dated/valueOf/requireCovered`, `Act.ADDENDUM`.
- Consumes (Task 2): `LeaseAddendum`, `LeaseAddendumRepository`, `LeaseLine.setAddendumId`, `EntryNumberService.nextDocumentNumber`.
- Consumes (existing, package-private on `LeasePostingService`): `lockLease(UUID)`, `planLines(Lease, List<LeaseLine>)`, `unmappedRoles(...)`, `periodLockErrors(LocalDate, List<Cheque>)`, `postTco(Lease, List<Pair>, LocalDate, String)`, `response(Lease, JournalEntry, List<Cheque>)`, `propertyIdOf(Lease)`.
- Produces:
  - `record AddChargeRequest(LocalDate effectiveFrom, LocalDate contractDate, String ejariNumber, String reason, List<LeaseLineInput> lines, List<ChequeRowInput> cheques)`
  - `record LeaseAddendumDTO(UUID id, String addendumNumber, LocalDate effectiveFrom, LocalDate contractDate, String ejariNumber, boolean ejariPending, String reason, BigDecimal value, UUID tcoJournalId, String tcoEntryNumber, Instant createdAt)`
  - `record AddendumResponse(LeaseAddendumDTO addendum, PostLeaseResponse posting)`
  - `record LeaseVariedEvent(UUID tenantId, UUID leaseId, UUID addendumId, List<UUID> lineIds)`
  - `LeaseVariationService.addCharge(UUID leaseId, AddChargeRequest r) : AddendumResponse`
  - `LeaseVariationService.list(UUID leaseId) : List<LeaseAddendumDTO>`
  - `LeaseVariationService.recordEjari(UUID leaseId, UUID addendumId, String ejariNumber) : LeaseAddendumDTO`

- [ ] **Step 1: DTOs and event**

`AddChargeRequest.java`:

```java
package com.datagami.rentaxis.api.dto.lease;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * Add a charge to a posted lease mid-term, as an addendum.
 *
 * <p>Additive, like an extension, but the end date does not move: every RENT line
 * covers {@code effectiveFrom} through the lease's current end date (or a window
 * inside it), and a fee is charged once. A DEPOSIT is refused. {@code cheques}
 * must total what the lines charge including VAT and are registered immediately.</p>
 *
 * @param contractDate the addendum's own date, which its TCO and the new cheques'
 *                     PDRs carry. Defaults to today.
 * @param ejariNumber  optional; blank means Ejari re-registration is pending.
 */
public record AddChargeRequest(@NotNull(message = "The addendum needs an effective date") LocalDate effectiveFrom,
                               LocalDate contractDate,
                               String ejariNumber,
                               String reason,
                               @NotEmpty(message = "At least one line is required")
                               List<LeaseLineInput> lines,
                               @NotEmpty(message = "At least one cheque row is required")
                               List<ChequeRowInput> cheques) {
}
```

`LeaseAddendumDTO.java`:

```java
package com.datagami.rentaxis.api.dto.lease;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LeaseAddendumDTO(UUID id,
                               String addendumNumber,
                               LocalDate effectiveFrom,
                               LocalDate contractDate,
                               String ejariNumber,
                               boolean ejariPending,
                               String reason,
                               BigDecimal value,
                               UUID tcoJournalId,
                               String tcoEntryNumber,
                               Instant createdAt) {
}
```

`AddendumResponse.java`:

```java
package com.datagami.rentaxis.api.dto.lease;

/** The addendum written, and the lease and register re-read after it posted. */
public record AddendumResponse(LeaseAddendumDTO addendum, PostLeaseResponse posting) {
}
```

`LeaseVariedEvent.java`:

```java
package com.datagami.rentaxis.core.service.lease;

import java.util.List;
import java.util.UUID;

/**
 * A posted lease took an addendum: lines appended, a further {@code TCO} and its
 * {@code PDR}s written, the end date unchanged. {@code lineIds} names the new
 * lines so recognition cuts a segment for each RENT one over its own window.
 * Published inside the transaction, like {@link LeaseExtendedEvent}.
 */
public record LeaseVariedEvent(UUID tenantId, UUID leaseId, UUID addendumId, List<UUID> lineIds) {
}
```

- [ ] **Step 2: Write the failing IT**

`LeaseVariationServiceIT.java`. Copy the imports, the `@Autowired` block and the helpers (`leaf`, `reread`, `linesOf`, `registerOf`, `leaseLines`, `balanceOf`, `journalEntryRows`, `tcosOf`, `postedWithFee`) verbatim from `LeaseRenewalServiceIT` (lines 1-256), then add these autowirings:

```java
    @Autowired LeaseVariationService variations;
    @Autowired LeaseAddendumRepository addenda;
    @Autowired RentSegmentRepository segmentRepo;
    @Autowired ChequeService chequeService;
```

Constants and helpers:

```java
    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);
    /** February, mid-way through the tenancy — the simulation's parking-bay month. */
    private static final LocalDate ADDENDUM_DATE = LocalDate.of(2027, 2, 10);
    private static final LocalDate EFFECTIVE = LocalDate.of(2027, 2, 15);

    private AddChargeRequest parking(String fee, String cheque) {
        return new AddChargeRequest(EFFECTIVE, ADDENDUM_DATE, null, "Parking bay P-12",
                List.of(line("PARKING_FEE", fee)),
                List.of(chequeRow(cheque, LocalDate.of(2027, 3, 1))));
    }

    /** Deposit then clear the lease's first cheque, so money has settled against the contract. */
    private Cheque clearFirstCheque(UUID leaseId) {
        Cheque first = registerOf(leaseId).get(0);
        fixtures.asTenantAdmin();
        chequeService.deposit(first.getId(), ChequeActionRequest.on(first.getChequeDate()));
        chequeService.clear(first.getId(), ChequeActionRequest.on(first.getChequeDate()));
        return tx.execute(s -> chequeRepo.findById(first.getId()).orElseThrow());
    }

    private String addNumber(int n) {
        return "ADD-" + String.format(java.util.Locale.ROOT, "%02d",
                tx.execute(s -> fiscal.fiscalYearOf(ADDENDUM_DATE)) % 100) + "/" + n;
    }
```

The tests (each one's javadoc carries the finding it proves):

```java
    /** #15: a cleared cheque does not stop an addition — nothing settled is disturbed. */
    @Test
    void anAddendumPostsOnALeaseWithAClearedCheque() {
        UUID leaseId = postedWithFee();
        UUID originalTcoId = reread(leaseId).getPostingJournalId();
        Cheque cleared = clearFirstCheque(leaseId);
        assertThat(cleared.getStatus()).isEqualTo(ChequeStatus.CLEARED);

        AddendumResponse r = variations.addCharge(leaseId, parking("6000", "6000"));

        assertThat(r.addendum().addendumNumber()).isEqualTo(addNumber(1));
        JournalEntry tco = tx.execute(s -> entries.findById(r.posting().tcoJournalId()).orElseThrow());
        assertThat(tco.getDocType()).isEqualTo(JournalDocType.TCO);
        assertThat(tco.getEntryDate()).isEqualTo(ADDENDUM_DATE);
        assertThat(tco.getNarration()).isEqualTo("Addendum " + addNumber(1) + ": Parking bay P-12");
        assertThat(linesOf(tco.getId())).extracting(JournalLine::getAccountId)
                .containsExactly(leaf(AccountRole.RENT_RECEIVABLE).getId(), leaf(AccountRole.PARKING_INCOME).getId());

        // Nothing that money settled against moved.
        assertThat(tx.execute(s -> entries.findById(originalTcoId).orElseThrow()).getStatus())
                .isEqualTo(JournalStatus.POSTED);
        assertThat(reread(leaseId).getPostingJournalId()).isEqualTo(originalTcoId);
        assertThat(tx.execute(s -> chequeRepo.findById(cleared.getId()).orElseThrow()).getStatus())
                .isEqualTo(ChequeStatus.CLEARED);
        assertThat(tcosOf(leaseId)).extracting(JournalEntry::getId).containsExactly(originalTcoId, tco.getId());

        // The new instrument is registered with its PDR.
        List<Cheque> register = registerOf(leaseId);
        assertThat(register).hasSize(6);
        assertThat(register.get(5).getAmount()).isEqualByComparingTo("6000");
        assertThat(register.get(5).getStatus()).isEqualTo(ChequeStatus.REGISTERED);
    }

    /** #17: the contract value rises by exactly the addendum, and the renter's receivable nets to zero. */
    @Test
    void theContractValueRisesByExactlyTheAddendum() {
        UUID leaseId = postedWithFee();
        BigDecimal before = leaseLines(leaseId).stream().map(LeaseLineDTO::netAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        variations.addCharge(leaseId, parking("6000", "6000"));

        BigDecimal after = leaseLines(leaseId).stream().map(LeaseLineDTO::netAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(after.subtract(before)).isEqualByComparingTo("6000");
        assertThat(balanceOf(leaf(AccountRole.RENT_RECEIVABLE), leaseId)).isEqualByComparingTo("0");
        assertThat(balanceOf(leaf(AccountRole.PARKING_INCOME), leaseId)).isEqualByComparingTo("-6000");
        assertThat(balanceOf(leaf(AccountRole.PDC_RECEIVABLE), leaseId)).isEqualByComparingTo("59000");
        assertThat(reread(leaseId).getEndDate()).isEqualTo(END);
    }

    /** A RENT addendum is recognised over its own window only; the original term is not recognised twice. */
    @Test
    void aRentAddendumGetsItsOwnSegmentOverTheAddendumWindow() {
        UUID leaseId = postedWithFee();
        int segmentsBefore = tx.execute(s -> segmentRepo.findByLease_IdOrderByFromDateAsc(leaseId)).size();

        AddendumResponse r = variations.addCharge(leaseId, new AddChargeRequest(EFFECTIVE, ADDENDUM_DATE, null,
                "Storage room", List.of(line("RENT", "4000")), List.of(chequeRow("4000", LocalDate.of(2027, 3, 1)))));

        List<RentSegment> segs = tx.execute(s -> segmentRepo.findByLease_IdOrderByFromDateAsc(leaseId));
        assertThat(segs).hasSize(segmentsBefore + 1);
        RentSegment added = segs.stream().filter(s -> s.getFromDate().equals(EFFECTIVE)).findFirst().orElseThrow();
        assertThat(added.getToDate()).isEqualTo(END);
        assertThat(added.getAmount()).isEqualByComparingTo("4000");
        assertThat(added.getDays()).isEqualTo(
                (int) java.time.temporal.ChronoUnit.DAYS.between(EFFECTIVE, END) + 1);
        // The new line is tied to its addendum.
        UUID addendumId = r.addendum().id();
        assertThat(tx.execute(s -> lineRepo.findByLease_IdOrderBySeqNoAsc(leaseId)).getLast().getAddendumId())
                .isEqualTo(addendumId);
    }

    @Test
    void aChequeMismatchChangesNothing() {
        UUID leaseId = postedWithFee();
        long journalsBefore = journalEntryRows();

        assertThatThrownBy(() -> variations.addCharge(leaseId, parking("6000", "5000")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cheque rows total 5,000.00 but the addendum charges 6,000.00");

        assertThat(leaseLines(leaseId)).hasSize(2);
        assertThat(registerOf(leaseId)).hasSize(5);
        assertThat(journalEntryRows()).isEqualTo(journalsBefore);
        assertThat(tx.execute(s -> addenda.findByLease_IdOrderByCreatedAtAsc(leaseId))).isEmpty();
    }

    @Test
    void aDepositLineIsRefused() {
        UUID leaseId = postedWithFee();
        assertThatThrownBy(() -> variations.addCharge(leaseId, new AddChargeRequest(EFFECTIVE, ADDENDUM_DATE, null, null,
                List.of(line("PARKING_DEPOSIT", "1000")), List.of(chequeRow("1000", LocalDate.of(2027, 3, 1))))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("an addendum cannot charge a deposit");
        assertThat(leaseLines(leaseId)).hasSize(2);
    }

    @Test
    void aLockedPeriodRefusesTheAddendumWhole() {
        UUID leaseId = postedWithFee();
        long journalsBefore = journalEntryRows();
        fiscal.lockThrough(LocalDate.of(2027, 2, 28));

        assertThatThrownBy(() -> variations.addCharge(leaseId, parking("6000", "6000")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cannot post on 2027-02-10: books are locked through 2027-02-28");
        assertThat(journalEntryRows()).isEqualTo(journalsBefore);
        assertThat(registerOf(leaseId)).hasSize(5);
    }

    @Test
    void anEffectiveDateOutsideTheTenancyIsRefused() {
        UUID leaseId = postedWithFee();
        assertThatThrownBy(() -> variations.addCharge(leaseId, new AddChargeRequest(END.plusDays(1), ADDENDUM_DATE,
                null, null, List.of(line("PARKING_FEE", "6000")), List.of(chequeRow("6000", LocalDate.of(2027, 3, 1))))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("must take effect within the tenancy");
    }

    @Test
    void aDraftLeaseCannotTakeAnAddendum() {
        UUID draft = fixtures.draftLease(CONTRACT_DATE, START, END, List.of(line("RENT", "51000")));
        assertThatThrownBy(() -> variations.addCharge(draft, parking("6000", "6000")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only an ACTIVE lease can take an addendum");
    }

    @Test
    void twoAddendaNumberInSequenceAndEjariCanBeRecordedLater() {
        UUID leaseId = postedWithFee();
        AddendumResponse first = variations.addCharge(leaseId, parking("6000", "6000"));
        AddendumResponse second = variations.addCharge(leaseId, parking("1200", "1200"));

        assertThat(first.addendum().addendumNumber()).isEqualTo(addNumber(1));
        assertThat(second.addendum().addendumNumber()).isEqualTo(addNumber(2));
        assertThat(first.addendum().ejariPending()).isTrue();

        LeaseAddendumDTO recorded = variations.recordEjari(leaseId, first.addendum().id(), "EJ-2027-00042");
        assertThat(recorded.ejariNumber()).isEqualTo("EJ-2027-00042");
        assertThat(recorded.ejariPending()).isFalse();
        assertThat(variations.list(leaseId)).extracting(LeaseAddendumDTO::ejariPending).containsExactly(false, true);
    }
```

Imports to add beyond the copied block: `AddChargeRequest`, `AddendumResponse`, `LeaseAddendumDTO`, `LeaseAddendumRepository`, `RentSegment`, `RentSegmentRepository`, `ChequeService`, `ChequeActionRequest`, `ChequeStatus`, `LeaseLineDTO`. The IDE/compiler will name each missing package; all of them are under `com.datagami.rentaxis`.

- [ ] **Step 3: Run it and confirm it fails**

Run: `cd backend && ./gradlew test --tests '*LeaseVariationServiceIT' ; rc=$? ; echo rc=$rc`
Expected: compilation FAILS with "cannot find symbol: class LeaseVariationService".

- [ ] **Step 4: Implement `LeaseVariationService`**

```java
package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.lease.AddChargeRequest;
import com.datagami.rentaxis.api.dto.lease.AddendumResponse;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.LeaseAddendumDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.ResourceNotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.ledger.EntryNumberService;
import com.datagami.rentaxis.core.service.ledger.UnmappedAccountRoleException;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseAddendum;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseAddendumRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A charge added to a posted lease mid-term, as a numbered addendum (findings
 * #15/#17, spec 2026-09-23-lease-addendum-design).
 *
 * <p>Additive in exactly the way an extension is: lines appended, a further
 * {@code TCO} for them alone, the cheques that pay for it registered on the spot.
 * The original TCO, {@code postingJournalId} and every existing cheque are left
 * alone — which is why a cleared cheque is no obstacle here, while it rightly is
 * to {@code amendLines}, which reverses and reposts every TCO.</p>
 *
 * <p>Same three stages as {@code LeaseRenewalService.extend}, for the same
 * reason: validate with nothing written, then write lines and rows and check the
 * accounts they resolve to, then — only once nothing can refuse — take the
 * {@code ADD} number and post.</p>
 */
@Service
public class LeaseVariationService {

    private final LeaseRepository leaseRepository;
    private final LeaseLineRepository leaseLineRepository;
    private final ChequeRepository chequeRepository;
    private final LeaseAddendumRepository addendumRepository;
    private final LeaseService leaseService;
    private final LeasePostingService postingService;
    private final ChequeGenerationService chequeGeneration;
    private final LeaseChequeRegistrar chequeRegistrar;
    private final AdditionalCharges charges;
    private final EntryNumberService entryNumbers;
    private final LeaseAccessPolicy leaseAccessPolicy;
    private final ApplicationEventPublisher events;

    public LeaseVariationService(LeaseRepository leaseRepository,
                                 LeaseLineRepository leaseLineRepository,
                                 ChequeRepository chequeRepository,
                                 LeaseAddendumRepository addendumRepository,
                                 LeaseService leaseService,
                                 LeasePostingService postingService,
                                 ChequeGenerationService chequeGeneration,
                                 LeaseChequeRegistrar chequeRegistrar,
                                 AdditionalCharges charges,
                                 EntryNumberService entryNumbers,
                                 LeaseAccessPolicy leaseAccessPolicy,
                                 ApplicationEventPublisher events) {
        this.leaseRepository = leaseRepository;
        this.leaseLineRepository = leaseLineRepository;
        this.chequeRepository = chequeRepository;
        this.addendumRepository = addendumRepository;
        this.leaseService = leaseService;
        this.postingService = postingService;
        this.chequeGeneration = chequeGeneration;
        this.chequeRegistrar = chequeRegistrar;
        this.charges = charges;
        this.entryNumbers = entryNumbers;
        this.leaseAccessPolicy = leaseAccessPolicy;
        this.events = events;
    }

    @Transactional
    public AddendumResponse addCharge(UUID leaseId, AddChargeRequest r) {
        Lease lease = postingService.lockLease(leaseId);
        leaseAccessPolicy.requireManageable(lease);

        if (lease.getStatus() != LeaseStatus.ACTIVE) {
            throw new BusinessRuleViolationException(
                    "Only an ACTIVE lease can take an addendum; this one is " + lease.getStatus() + ".");
        }
        if (lease.getPostingJournalId() == null) {
            throw new BusinessRuleViolationException(
                    "This lease has not been posted; add the charge to its draft lines instead.");
        }
        if (r == null || r.effectiveFrom() == null) {
            throw new BusinessRuleViolationException("The addendum needs an effective date");
        }
        LocalDate effectiveFrom = r.effectiveFrom();
        if (effectiveFrom.isBefore(lease.getStartDate()) || effectiveFrom.isAfter(lease.getEndDate())) {
            throw new BusinessRuleViolationException("The addendum must take effect within the tenancy ("
                    + lease.getStartDate() + " to " + lease.getEndDate() + "), not " + effectiveFrom + ".");
        }
        LocalDate entryDate = r.contractDate() != null ? r.contractDate() : LocalDate.now();

        // ---- stage 1: the request, with nothing written -------------------
        List<LeaseLineInput> inputs = charges.dated(r.lines(), effectiveFrom, lease.getEndDate(),
                AdditionalCharges.Act.ADDENDUM);
        BigDecimal charged = charges.valueOf(inputs);
        List<ChequeRowInput> rows = r.cheques() == null ? List.of() : r.cheques();
        charges.requireCovered(rows, charged, AdditionalCharges.Act.ADDENDUM);

        List<String> lockErrors = postingService.periodLockErrors(entryDate, List.of());
        if (!lockErrors.isEmpty()) {
            throw new BusinessRuleViolationException(String.join(" ", lockErrors));
        }

        // ---- stage 2: rows exist, journals do not --------------------------
        List<LeaseLine> newLines = leaseService.appendLines(lease, inputs);
        List<Cheque> newRows = chequeGeneration.appendRows(lease, rows, entryDate);

        LeasePostingService.LinePlan plan = postingService.planLines(lease, newLines);
        Set<AccountRole> missing = postingService.unmappedRoles(lease, newLines, newRows);
        List<String> problems = new ArrayList<>(plan.errors());
        problems.addAll(postingService.periodLockErrors(entryDate, newRows));
        if (problems.isEmpty() && !missing.isEmpty()) {
            throw new UnmappedAccountRoleException(missing, LeasePostingService.propertyIdOf(lease));
        }
        if (!missing.isEmpty()) {
            problems.add(new UnmappedAccountRoleException(missing, LeasePostingService.propertyIdOf(lease)).getMessage());
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(String.join(" ", problems));
        }

        // ---- stage 3: the number and the journals ------------------------
        String number = entryNumbers.nextDocumentNumber("ADD", entryDate);
        String reason = r.reason() == null || r.reason().isBlank() ? null : r.reason().trim();
        JournalEntry tco = postingService.postTco(lease, plan.pairs(), entryDate,
                "Addendum " + number + (reason == null ? "" : ": " + reason));
        for (Cheque row : newRows) {
            chequeRegistrar.register(lease, row);
        }

        LeaseAddendum addendum = new LeaseAddendum();
        addendum.setLease(lease);
        addendum.setAddendumNumber(number);
        addendum.setEffectiveFrom(effectiveFrom);
        addendum.setContractDate(entryDate);
        addendum.setEjariNumber(blankToNull(r.ejariNumber()));
        addendum.setReason(reason);
        addendum.setValue(charged);
        addendum.setTcoJournalId(tco.getId());
        addendum.setTcoEntryNumber(tco.getEntryNumber());
        addendum.setCreatedBy(LeaseAccessPolicy.currentUserIdOrNull());
        addendum = addendumRepository.save(addendum);

        for (LeaseLine line : newLines) {
            line.setAddendumId(addendum.getId());
        }
        leaseLineRepository.saveAll(newLines);

        leaseService.syncDerivedTotals(lease);
        leaseRepository.save(lease);

        leaseService.recordLeaseEvent(lease, LeaseStatus.ACTIVE, LeaseStatus.ACTIVE,
                "Addendum " + number + " from " + effectiveFrom + ", posted as " + tco.getEntryNumber()
                        + (addendum.getEjariNumber() == null ? "; Ejari re-registration pending" : ""));
        events.publishEvent(new LeaseVariedEvent(lease.getTenantId(), lease.getId(), addendum.getId(),
                newLines.stream().map(LeaseLine::getId).toList()));

        return new AddendumResponse(toDto(addendum), postingService.response(lease, tco,
                chequeRepository.findByLease_IdOrderBySeqNoAsc(lease.getId())));
    }

    @Transactional(readOnly = true)
    public List<LeaseAddendumDTO> list(UUID leaseId) {
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Lease not found"));
        leaseAccessPolicy.requireReadable(lease);
        return addendumRepository.findByLease_IdOrderByCreatedAtAsc(leaseId).stream().map(LeaseVariationService::toDto).toList();
    }

    @Transactional
    public LeaseAddendumDTO recordEjari(UUID leaseId, UUID addendumId, String ejariNumber) {
        Lease lease = postingService.lockLease(leaseId);
        leaseAccessPolicy.requireManageable(lease);
        String ejari = blankToNull(ejariNumber);
        if (ejari == null) {
            throw new BusinessRuleViolationException("An Ejari number is required");
        }
        LeaseAddendum addendum = addendumRepository.findByIdAndLease_Id(addendumId, leaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Addendum not found"));
        addendum.setEjariNumber(ejari);
        addendum = addendumRepository.save(addendum);
        leaseService.recordLeaseEvent(lease, lease.getStatus(), lease.getStatus(),
                "Ejari " + ejari + " recorded for addendum " + addendum.getAddendumNumber());
        return toDto(addendum);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static LeaseAddendumDTO toDto(LeaseAddendum a) {
        return new LeaseAddendumDTO(a.getId(), a.getAddendumNumber(), a.getEffectiveFrom(), a.getContractDate(),
                a.getEjariNumber(), a.getEjariNumber() == null, a.getReason(), a.getValue(),
                a.getTcoJournalId(), a.getTcoEntryNumber(), a.getCreatedAt());
    }
}
```

Three names above must be checked against the code before compiling. Use whatever exists; do not invent a new helper if an equivalent is already there:
- `LeaseAccessPolicy.requireReadable`. Run `grep -n 'public void require' backend/src/main/java/com/datagami/rentaxis/core/security/LeaseAccessPolicy.java`. If the read check has another name, use it.
- `LeaseAccessPolicy.currentUserIdOrNull`. `LeasePostingService` has a private `currentUserId()` at line 965. Copy its body into a private static `currentUserId()` in this class if no shared helper exists.
- `ResourceNotFoundException`. Run `ls backend/src/main/java/com/datagami/rentaxis/api/exception/`. Use the not-found exception there that maps to 404.

- [ ] **Step 5: Recognition listener**

In `RecognitionEventListener`, after `onLeaseExtended`:

```java
    /**
     * An addendum's RENT lines get a segment each over their own window, exactly
     * as an extension's do; its fees have no schedule.
     */
    @EventListener
    public void onLeaseVaried(LeaseVariedEvent event) {
        recognition.appendForExtension(event.leaseId(), event.lineIds());
    }
```

Add `import com.datagami.rentaxis.core.service.lease.LeaseVariedEvent;`.

- [ ] **Step 6: Run the IT and the golden gate**

Run: `cd backend && ./gradlew test --tests '*LeaseVariationServiceIT' ; rc=$? ; echo rc=$rc`
Expected: `rc=0`, 9 tests passing.

Run: `cd backend && ./gradlew test -PincludeTags=golden ; rc=$? ; echo rc=$rc`
Expected: `rc=0`.

Mutation-check the #15 test. Temporarily add a guard at the top of `addCharge` that refuses when any cheque is CLEARED. `anAddendumPostsOnALeaseWithAClearedCheque` must go red. Remove the guard and confirm it is green again.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/dto/lease/AddChargeRequest.java \
        backend/src/main/java/com/datagami/rentaxis/api/dto/lease/LeaseAddendumDTO.java \
        backend/src/main/java/com/datagami/rentaxis/api/dto/lease/AddendumResponse.java \
        backend/src/main/java/com/datagami/rentaxis/core/service/lease/LeaseVariedEvent.java \
        backend/src/main/java/com/datagami/rentaxis/core/service/lease/LeaseVariationService.java \
        backend/src/main/java/com/datagami/rentaxis/core/service/recognition/RecognitionEventListener.java \
        backend/src/test/java/com/datagami/rentaxis/core/service/lease/LeaseVariationServiceIT.java
git commit -m "feat: add a charge to a posted lease mid-term as an addendum (#15, #17)

A further TCO for the new lines alone, the cheques that pay for it
registered on the spot, numbered ADD-yy/n. The original TCO and every
existing cheque are untouched, so a cleared cheque no longer blocks it.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: HTTP endpoints and role gates

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/lease/RecordEjariRequest.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/LeaseController.java` (after `extendLease`, line ~403; add a `LeaseVariationService` constructor dependency next to `leaseRenewalService`)
- Test: `backend/src/test/java/com/datagami/rentaxis/api/LeaseControllerAddendumEndpointsIT.java`

**Interfaces:**
- Consumes (Task 3): `LeaseVariationService.addCharge/list/recordEjari`.
- Produces (HTTP):
  - `POST /api/v1/leases/{id}/addenda` (body `AddChargeRequest`) → `AddendumResponse`. Roles: SUPER_ADMIN, TENANT_ADMIN, ACCOUNTANT.
  - `GET /api/v1/leases/{id}/addenda` → `LeaseAddendumDTO[]`. Roles: those four plus PROPERTY_MANAGER.
  - `PATCH /api/v1/leases/{id}/addenda/{addendumId}/ejari` (body `{ "ejariNumber": "..." }`) → `LeaseAddendumDTO`. Roles: SUPER_ADMIN, TENANT_ADMIN, ACCOUNTANT.

- [ ] **Step 1: Write the failing role IT**

Copy `LeaseControllerRenewExtendEndpointsIT` (setUp, tearDown, `user`, `request`) into `LeaseControllerAddendumEndpointsIT`. Rename the class, change the javadoc to describe addenda, and replace the tests with:

```java
    private AddChargeRequest body() {
        return new AddChargeRequest(LocalDate.of(2027, 2, 15), LocalDate.of(2027, 2, 10), null, "Parking",
                List.of(line("PARKING_FEE", "6000")),
                List.of(chequeRow("6000", LocalDate.of(2027, 3, 1))));
    }

    @Test
    void anAccountantMayAddAChargeAndRecordItsEjari() {
        AddendumResponse r = request(accountant, HttpMethod.POST, "/api/v1/leases/" + leaseId + "/addenda", body())
                .body(AddendumResponse.class);
        assertThat(r.addendum().addendumNumber()).startsWith("ADD-");
        assertThat(r.addendum().ejariPending()).isTrue();

        LeaseAddendumDTO patched = request(accountant, HttpMethod.PATCH,
                "/api/v1/leases/" + leaseId + "/addenda/" + r.addendum().id() + "/ejari",
                new RecordEjariRequest("EJ-1")).body(LeaseAddendumDTO.class);
        assertThat(patched.ejariPending()).isFalse();
    }

    @Test
    void aPropertyManagerMayReadAddendaButNotPostOne() {
        assertThat(status(propertyManager, HttpMethod.POST, "/api/v1/leases/" + leaseId + "/addenda", body()))
                .isEqualTo(403);
        assertThat(status(propertyManager, HttpMethod.GET, "/api/v1/leases/" + leaseId + "/addenda", null))
                .isEqualTo(200);
    }

    @Test
    void rentersAndTenantUsersMayNotPostAnAddendum() {
        assertThat(status(renter, HttpMethod.POST, "/api/v1/leases/" + leaseId + "/addenda", body())).isEqualTo(403);
        assertThat(status(tenantUser, HttpMethod.POST, "/api/v1/leases/" + leaseId + "/addenda", body())).isEqualTo(403);
    }
```

`status(...)` is the same status-extracting helper the copied IT uses. Copy it with the rest, and keep its exact name if it differs from `status`.

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd backend && ./gradlew test --tests '*LeaseControllerAddendumEndpointsIT' ; rc=$? ; echo rc=$rc`
Expected: FAIL. `RecordEjariRequest` doesn't exist yet, so compilation fails. Once it exists, the POST returns 404 because there's no mapping.

- [ ] **Step 3: Implement**

`RecordEjariRequest.java`:

```java
package com.datagami.rentaxis.api.dto.lease;

import jakarta.validation.constraints.NotBlank;

public record RecordEjariRequest(@NotBlank(message = "An Ejari number is required") String ejariNumber) {
}
```

In `LeaseController`, add the constructor-injected `LeaseVariationService leaseVariationService` and, after `extendLease`:

```java
    /**
     * Add a charge to a posted lease mid-term, as a numbered addendum: a further
     * TCO for the new lines, the cheques that pay for it registered on the spot.
     * Finance roles only, like /extend — it posts immediately.
     */
    @PostMapping("/{id}/addenda")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<AddendumResponse> addCharge(@PathVariable UUID id,
                                                      @Valid @RequestBody AddChargeRequest request) {
        return ResponseEntity.ok(leaseVariationService.addCharge(id, request));
    }

    @GetMapping("/{id}/addenda")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
    public ResponseEntity<List<LeaseAddendumDTO>> listAddenda(@PathVariable UUID id) {
        return ResponseEntity.ok(leaseVariationService.list(id));
    }

    /** Fill in the Ejari a variation was re-registered under; blank until then ("Ejari pending"). */
    @PatchMapping("/{id}/addenda/{addendumId}/ejari")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<LeaseAddendumDTO> recordAddendumEjari(@PathVariable UUID id,
                                                               @PathVariable UUID addendumId,
                                                               @Valid @RequestBody RecordEjariRequest request) {
        return ResponseEntity.ok(leaseVariationService.recordEjari(id, addendumId, request.ejariNumber()));
    }
```

Add imports: `AddChargeRequest`, `AddendumResponse`, `LeaseAddendumDTO`, `RecordEjariRequest`, `LeaseVariationService`, `org.springframework.web.bind.annotation.PatchMapping`.

- [ ] **Step 4: Run it**

Run: `cd backend && ./gradlew test --tests '*LeaseControllerAddendumEndpointsIT' --tests '*LeaseVariationServiceIT' ; rc=$? ; echo rc=$rc`
Expected: `rc=0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/dto/lease/RecordEjariRequest.java \
        backend/src/main/java/com/datagami/rentaxis/api/LeaseController.java \
        backend/src/test/java/com/datagami/rentaxis/api/LeaseControllerAddendumEndpointsIT.java
git commit -m "feat: expose lease addenda over HTTP with finance-only posting

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Web: Add charge, the addenda panel, and the #17 message

**Files:**
- Create: `web/src/components/cheques/ChequeRowsEditor.tsx`
- Modify: `web/src/components/leases/ExtendLeaseDialog.tsx`
- Create: `web/src/components/leases/AddChargeDialog.tsx`
- Create: `web/src/components/leases/LeaseAddendaPanel.tsx`
- Modify: `web/src/lib/api/leasing.ts` (types after `ExtendLeaseInput`, line ~294; client calls after `extend`, line 892)
- Modify: `web/src/app/[locale]/dashboard/leases/[id]/page.tsx` (state near line 174, button after the Extend button at ~line 520, dialog after `ExtendLeaseDialog` at ~line 951, panel beside the existing lease sections)
- Modify: `web/messages/en.json`, `web/messages/ar.json` (the `Leasing` namespace; `amendCannotReprice` at en.json:1799)
- Test: `web/src/components/leases/__tests__/AddChargeDialog.test.tsx`, `web/src/components/leases/__tests__/LeaseAddendaPanel.test.tsx`; existing `ExtendLeaseDialog.test.tsx` and `AmendLinesDialog.test.tsx` must stay green

**Interfaces:**
- Consumes (Task 4): the three endpoints.
- Produces:
  - `AddChargeInput`, `LeaseAddendum`, `AddendumResponse` TS types.
  - `leaseApi.addCharge(id, body)`, `leaseApi.addenda(id)` and `leaseApi.recordAddendumEjari(id, addendumId, ejariNumber)`.
  - `<ChequeRowsEditor rows onChange propertyId expectedTotal testIdPrefix />`.

- [ ] **Step 1: API client**

In `leasing.ts`, after `ExtendLeaseInput`:

```ts
/** AddChargeRequest — a mid-term charge on a posted lease, as an addendum. */
export type AddChargeInput = {
  effectiveFrom: string;
  contractDate?: string | null;
  ejariNumber?: string | null;
  reason?: string | null;
  lines: LeaseLineInput[];
  cheques: ChequeRowInput[];
};

/** LeaseAddendumDTO. `ejariPending` is true until an Ejari number is recorded. */
export type LeaseAddendum = {
  id: string;
  addendumNumber: string;
  effectiveFrom: string;
  contractDate: string;
  ejariNumber: string | null;
  ejariPending: boolean;
  reason: string | null;
  value: number;
  tcoJournalId: string;
  tcoEntryNumber: string;
  createdAt: string;
};

/** AddendumResponse. */
export type AddendumResponse = { addendum: LeaseAddendum; posting: PostLeaseResponse };
```

After the `extend:` entry in `leaseApi`:

```ts
  addCharge: (id: string, body: AddChargeInput) => send<AddendumResponse>("POST", `/leases/${id}/addenda`, body),
  addenda: (id: string) => get<LeaseAddendum[]>(`/leases/${id}/addenda`),
  recordAddendumEjari: (id: string, addendumId: string, ejariNumber: string) =>
    send<LeaseAddendum>("PATCH", `/leases/${id}/addenda/${addendumId}/ejari`, { ejariNumber }),
```

If `send` does not accept `"PATCH"`, widen its method union type where `send` is defined in the same file.

- [ ] **Step 2: Extract `ChequeRowsEditor`**

Move the cheque-grid `<section>` body of `ExtendLeaseDialog.tsx` (the `<div className="bg-surface …">` table through the row-error `<ul>`), along with `blankChequeRow`, `stripKey`, `ChequeDraft`, `MODES`, `field` and `td`, into `web/src/components/cheques/ChequeRowsEditor.tsx`:

```tsx
"use client";

import { useTranslations } from "next-intl";
import { Plus, Trash2 } from "lucide-react";
import { NumberInput } from "@/components/ui/NumberInput";
import SettlementAccountPicker from "@/components/finance/SettlementAccountPicker";
import { fmtAmount } from "@/lib/api/ledger";
import { round2, todayIso } from "@/components/leases/leaseMath";
import { TYPEABLE_MODES, chequeRowsErrors } from "@/components/cheques/chequeRowRules";
import type { ChequeMode, ChequeRowInput } from "@/lib/api/leasing";

/**
 * The cheque rows that pay for a charge added to a posted lease — an
 * extension's or an addendum's. They register the moment the dialog posts, so
 * the grid shows whether Σ rows equals what the lines charge (VAT included),
 * the same figure the server compares.
 */

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const td = "px-2 py-1.5 text-xs";
const MODES: ChequeMode[] = TYPEABLE_MODES;

/** A row of the grid. `key` is React's, not the server's. */
export type ChequeDraft = ChequeRowInput & { key: number };

/**
 * A PDC needs the date written on it (ChequeRowRules.java:140-141), so the
 * date it is expected on starts equal to its posting date rather than empty.
 */
export function blankChequeRow(key: number): ChequeDraft {
    const today = todayIso();
    return { key, amount: 0, mode: "PDC", postingDate: today, chequeDate: today };
}

export function stripKey(row: ChequeDraft): ChequeRowInput {
    const copy: Partial<ChequeDraft> = { ...row };
    delete copy.key;
    return copy as ChequeRowInput;
}

export function chequeTotalOf(rows: ChequeDraft[]): number {
    return rows.reduce((s, c) => round2(s + (c.amount || 0)), 0);
}

type Props = {
    rows: ChequeDraft[];
    onChange: (rows: ChequeDraft[]) => void;
    propertyId: string;
    /** What the lines charge including VAT; Σ rows must equal it. */
    expectedTotal: number;
    /** `extend` keeps the test ids ExtendLeaseDialog's tests already use. */
    testIdPrefix: string;
};

export default function ChequeRowsEditor({ rows, onChange, propertyId, expectedTotal, testIdPrefix }: Props) {
    const t = useTranslations("Leasing");
    const tc = useTranslations("Cheques");
    const total = chequeTotalOf(rows);
    const matches = Math.abs(round2(total - expectedTotal)) < 0.005;
    const errors = chequeRowsErrors(rows.map(stripKey));
    /** An untouched default row is not a mistake yet; its "amount must be positive" waits. */
    const shown = errors.map((errs, i) => (rows[i]?.amount ? errs : errs.filter(e => e.code !== "amountPositive")));
    const patch = (key: number, next: Partial<ChequeDraft>) =>
        onChange(rows.map(c => (c.key === key ? { ...c, ...next } : c)));

    return (
        <>
            {/* PASTE HERE, unchanged: the <div className="bg-surface border ..."> block from
                ExtendLeaseDialog.tsx (the <table data-testid="extend-cheque-grid"> through the
                add/match footer) and the row-error <ul>, with these replacements only:
                  cheques.map        -> rows.map
                  patchCheque(       -> patch(
                  setCheques(cs => cs.filter(x => x.key !== c.key))
                                     -> onChange(rows.filter(x => x.key !== c.key))
                  setCheques(cs => [...cs, blankChequeRow(cs.reduce(...) + 1)])
                                     -> onChange([...rows, blankChequeRow(rows.reduce((m, c) => Math.max(m, c.key), -1) + 1)])
                  lease.propertyId   -> propertyId
                  chequeTotal        -> total
                  totals.inclVat     -> expectedTotal
                  shownRowErrors     -> shown
                  "extend-cheque-grid" / "extend-add-cheque" / "extend-match" / "extend-cheque-row-errors"
                                     -> `${testIdPrefix}-cheque-grid` / `${testIdPrefix}-add-cheque` /
                                        `${testIdPrefix}-match` / `${testIdPrefix}-cheque-row-errors` */}
        </>
    );
}
```

This comment names an exact, mechanical move of existing JSX (about 150 lines). The implementer does that move and deletes the comment. It isn't a placeholder for unwritten logic, but the finished file must contain no comment block.

In `ExtendLeaseDialog.tsx`:
- Import `ChequeRowsEditor, { blankChequeRow, chequeTotalOf, stripKey, type ChequeDraft }` from `@/components/cheques/ChequeRowsEditor`.
- Delete the moved local helpers and `patchCheque`/`shownRowErrors`.
- Compute `const matches = Math.abs(round2(chequeTotalOf(cheques) - totals.inclVat)) < 0.005;` and `const chequeRows = cheques.map(stripKey);`.
- Replace the moved JSX inside the "extensionCheques" `<section>` with:

```tsx
<ChequeRowsEditor
    rows={cheques}
    onChange={setCheques}
    propertyId={lease.propertyId}
    expectedTotal={totals.inclVat}
    testIdPrefix="extend"
/>
```

Run: `cd web && npx vitest run src/components/leases/__tests__/ExtendLeaseDialog.test.tsx ; rc=$? ; echo rc=$rc`
Expected: `rc=0`. The extraction keeps behaviour and test ids.

- [ ] **Step 3: i18n keys (both locales, surgical insert)**

Add these keys inside `"Leasing"`, directly after the `"extendFailed"` line in each file. Insert by line number with a small script or the editor; do not re-serialize the file.

en.json:

```json
    "addCharge": "Add charge",
    "addChargeFailed": "The addendum could not be posted.",
    "addendumLines": "Addendum charges",
    "addendumCheques": "Cheques for the addendum",
    "effectiveFrom": "Effective from",
    "addendumReason": "Reason",
    "ejariNumberOptional": "Ejari number (optional)",
    "addenda": "Addenda",
    "noAddenda": "No addenda on this lease.",
    "ejariPending": "Ejari pending",
    "recordEjari": "Record Ejari",
    "recordEjariFailed": "The Ejari number could not be saved.",
```

ar.json:

```json
    "addCharge": "إضافة رسوم",
    "addChargeFailed": "تعذّر ترحيل الملحق.",
    "addendumLines": "رسوم الملحق",
    "addendumCheques": "شيكات الملحق",
    "effectiveFrom": "ساري اعتباراً من",
    "addendumReason": "السبب",
    "ejariNumberOptional": "رقم إيجاري (اختياري)",
    "addenda": "الملاحق",
    "noAddenda": "لا توجد ملاحق على هذا العقد.",
    "ejariPending": "إيجاري قيد الانتظار",
    "recordEjari": "تسجيل إيجاري",
    "recordEjariFailed": "تعذّر حفظ رقم إيجاري.",
```

Rewrite `amendCannotReprice` in both files (#17: stop pointing at an action the UI does not have):
- en: `"An amendment redistributes the contract value between the lines; it cannot change the total. To charge more, use Add charge — it posts an addendum for the new charge alone."`
- ar: `"التعديل يعيد توزيع قيمة العقد بين البنود ولا يغيّر إجماليها. لإضافة رسوم جديدة استخدم «إضافة رسوم» — إذ يُرحَّل ملحق للرسوم الجديدة وحدها."`

Validate: `cd web && node -e "JSON.parse(require('fs').readFileSync('messages/en.json','utf8'));JSON.parse(require('fs').readFileSync('messages/ar.json','utf8'));console.log('ok')"`
Expected: `ok`. Then `git diff --stat web/messages` should show only a handful of changed lines per file, not thousands.

Update the assertion in `AmendLinesDialog.test.tsx:122` (the #267 test) if it matches the old sentence text.

- [ ] **Step 4: Write the failing dialog test**

`web/src/components/leases/__tests__/AddChargeDialog.test.tsx`:

```tsx
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import type { ChargeType, LeaseDetail } from "@/lib/api/leasing";

vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div data-testid="account-picker" /> }));
vi.mock("@/components/finance/SettlementAccountPicker", () => ({ default: () => <div /> }));
vi.mock("@/components/leases/LeaseLinesGrid", () => ({ default: () => <div data-testid="lines-grid" /> }));
vi.mock("@/components/leases/leaseMath", async orig => {
    const m = await orig<typeof import("@/components/leases/leaseMath")>();
    return { ...m, linesAreValid: () => true, totalsOf: () => ({ gross: 6000, discount: 0, net: 6000, vat: 0, inclVat: 6000 }) };
});
const addCharge = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, addCharge: (...a: unknown[]) => addCharge(...a) } };
});

import AddChargeDialog from "../AddChargeDialog";

const LEASE = { id: "lease-1", propertyId: "p1", startDate: "2026-10-02", endDate: "2027-10-01" } as unknown as LeaseDetail;
const CHARGE_TYPES: ChargeType[] = [];

function renderDialog(onAdded = vi.fn()) {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <AddChargeDialog open lease={LEASE} chargeTypes={CHARGE_TYPES} onClose={() => {}} onAdded={onAdded} />
        </NextIntlClientProvider>,
    );
    return onAdded;
}

afterEach(() => { cleanup(); addCharge.mockReset(); });

describe("AddChargeDialog", () => {
    it("stays disabled until an effective date inside the tenancy is chosen and the cheques cover the charge", () => {
        renderDialog();
        const confirm = screen.getByTestId("add-charge-confirm");
        expect(confirm).toBeDisabled();

        fireEvent.change(screen.getByTestId("add-charge-effective-from"), { target: { value: "2027-02-15" } });
        expect(confirm).toBeDisabled(); // default cheque row is 0, lines charge 6,000

        fireEvent.change(screen.getByLabelText("Amount 1"), { target: { value: "6000" } });
        expect(screen.getByTestId("add-charge-match")).toHaveAttribute("data-match", "true");
        expect(confirm).not.toBeDisabled();

        fireEvent.change(screen.getByTestId("add-charge-effective-from"), { target: { value: "2027-10-02" } });
        expect(confirm).toBeDisabled(); // after the lease end
    });

    it("posts the addendum with a blank Ejari sent as null", async () => {
        addCharge.mockResolvedValue({ addendum: { addendumNumber: "ADD-27/1" }, posting: {} });
        const onAdded = renderDialog();
        fireEvent.change(screen.getByTestId("add-charge-effective-from"), { target: { value: "2027-02-15" } });
        fireEvent.change(screen.getByLabelText("Amount 1"), { target: { value: "6000" } });
        fireEvent.click(screen.getByTestId("add-charge-confirm"));

        await waitFor(() => expect(onAdded).toHaveBeenCalled());
        expect(addCharge).toHaveBeenCalledWith("lease-1", expect.objectContaining({
            effectiveFrom: "2027-02-15", ejariNumber: null,
        }));
    });
});
```

Label text: the amount input's aria-label is `` `${t("amount")} ${i + 1}` ``. Check `Leasing.amount` in en.json and adjust `"Amount 1"` if the key reads differently. The existing Extend test uses `"Date 1"` for `chequeDate`, so check `amount` the same way.

Run: `cd web && npx vitest run src/components/leases/__tests__/AddChargeDialog.test.tsx ; rc=$? ; echo rc=$rc`
Expected: FAIL, "Failed to resolve import ../AddChargeDialog".

- [ ] **Step 5: Implement `AddChargeDialog`**

```tsx
"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import LeaseDialog from "./LeaseDialog";
import LeaseLinesGrid from "./LeaseLinesGrid";
import ChequeRowsEditor, { blankChequeRow, chequeTotalOf, stripKey, type ChequeDraft } from "@/components/cheques/ChequeRowsEditor";
import { blankLine, linesAreValid, round2, splitLineErrors, toInputs, todayIso, totalsOf, type LineRow } from "./leaseMath";
import { chequeRowsAreValid } from "@/components/cheques/chequeRowRules";
import { ApiError, leaseApi, type AddendumResponse, type ChargeType, type LeaseDetail } from "@/lib/api/leasing";

/**
 * Add a charge to a posted lease mid-term — a parking bay, a storage room — as
 * a numbered addendum. It posts immediately: a further TCO for the new lines
 * only and a PDR per new cheque, so the totals must agree before the button is
 * live. The end date does not move; a rent line runs from the effective date to
 * the lease's end. Ejari may be left blank and recorded later.
 */

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

type Props = {
    open: boolean;
    lease: LeaseDetail;
    chargeTypes: ChargeType[];
    onClose: () => void;
    onAdded: (res: AddendumResponse) => void;
};

export default function AddChargeDialog({ open, lease, chargeTypes, onClose, onAdded }: Props) {
    const t = useTranslations("Leasing");
    const [effectiveFrom, setEffectiveFrom] = useState("");
    const [contractDate, setContractDate] = useState(todayIso());
    const [ejariNumber, setEjariNumber] = useState("");
    const [reason, setReason] = useState("");
    const [rows, setRows] = useState<LineRow[]>([]);
    const [cheques, setCheques] = useState<ChequeDraft[]>([]);
    const [busy, setBusy] = useState(false);
    const [errors, setErrors] = useState<string[]>([]);

    useEffect(() => {
        if (!open) return;
        setEffectiveFrom("");
        setContractDate(todayIso());
        setEjariNumber("");
        setReason("");
        setRows([blankLine(0)]);
        setCheques([blankChequeRow(0)]);
        setErrors([]);
    }, [open]);

    const totals = totalsOf(rows, chargeTypes);
    const matches = Math.abs(round2(chequeTotalOf(cheques) - totals.inclVat)) < 0.005;
    const chequeRows = cheques.map(stripKey);
    const inTenancy = !!effectiveFrom && effectiveFrom >= lease.startDate && effectiveFrom <= lease.endDate;
    const { rest } = splitLineErrors(errors);

    const submit = async () => {
        setBusy(true);
        setErrors([]);
        try {
            const res = await leaseApi.addCharge(lease.id, {
                effectiveFrom,
                contractDate: contractDate || null,
                ejariNumber: ejariNumber.trim() || null,
                reason: reason.trim() || null,
                lines: toInputs(rows),
                cheques: chequeRows,
            });
            onAdded(res);
        } catch (e) {
            setErrors(e instanceof ApiError ? [e.message] : [t("addChargeFailed")]);
        } finally {
            setBusy(false);
        }
    };

    return (
        <LeaseDialog
            open={open}
            title={t("addCharge")}
            onClose={onClose}
            onConfirm={submit}
            confirmText={t("addCharge")}
            cancelText={t("cancel")}
            confirmDisabled={!inTenancy || !matches || !linesAreValid(rows) || !chequeRowsAreValid(chequeRows)}
            busy={busy}
            confirmTestId="add-charge-confirm"
            width="xl"
        >
            <div className="space-y-4">
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                    <div>
                        <label className={label} htmlFor="add-charge-effective-from">{t("effectiveFrom")}</label>
                        <input id="add-charge-effective-from" data-testid="add-charge-effective-from" type="date"
                               min={lease.startDate} max={lease.endDate} className={field}
                               value={effectiveFrom} onChange={e => setEffectiveFrom(e.target.value)} />
                    </div>
                    <div>
                        <label className={label} htmlFor="add-charge-contract-date">{t("contractDate")}</label>
                        <input id="add-charge-contract-date" type="date" className={field}
                               value={contractDate} onChange={e => setContractDate(e.target.value)} />
                    </div>
                    <div>
                        <label className={label} htmlFor="add-charge-ejari">{t("ejariNumberOptional")}</label>
                        <input id="add-charge-ejari" className={field}
                               value={ejariNumber} onChange={e => setEjariNumber(e.target.value)} />
                    </div>
                    <div>
                        <label className={label} htmlFor="add-charge-reason">{t("addendumReason")}</label>
                        <input id="add-charge-reason" className={field}
                               value={reason} onChange={e => setReason(e.target.value)} />
                    </div>
                </div>

                <section>
                    <h4 className="text-[11px] font-semibold text-muted uppercase tracking-wider mb-2">{t("addendumLines")}</h4>
                    <LeaseLinesGrid lines={rows} chargeTypes={chargeTypes} propertyId={lease.propertyId}
                                    editable onChange={setRows} errors={errors} />
                </section>

                <section>
                    <h4 className="text-[11px] font-semibold text-muted uppercase tracking-wider mb-2">{t("addendumCheques")}</h4>
                    <ChequeRowsEditor rows={cheques} onChange={setCheques} propertyId={lease.propertyId}
                                      expectedTotal={totals.inclVat} testIdPrefix="add-charge" />
                </section>

                {rest.length > 0 && (
                    <ul className="text-[11px] text-error space-y-1" data-testid="add-charge-errors">
                        {rest.map((e, i) => <li key={i}>{e}</li>)}
                    </ul>
                )}
            </div>
        </LeaseDialog>
    );
}
```

Check that `LeaseDetail` has `startDate`: `grep -n 'startDate' web/src/lib/api/leasing.ts | head -3`.

- [ ] **Step 6: `LeaseAddendaPanel` and its test**

`web/src/components/leases/__tests__/LeaseAddendaPanel.test.tsx`:

```tsx
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";

const recordAddendumEjari = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, recordAddendumEjari: (...a: unknown[]) => recordAddendumEjari(...a) } };
});

import LeaseAddendaPanel from "../LeaseAddendaPanel";

const PENDING = {
    id: "a1", addendumNumber: "ADD-27/1", effectiveFrom: "2027-02-15", contractDate: "2027-02-10",
    ejariNumber: null, ejariPending: true, reason: "Parking", value: 6000,
    tcoJournalId: "j1", tcoEntryNumber: "TCO-27/9", createdAt: "2027-02-10T00:00:00Z",
};

afterEach(() => { cleanup(); recordAddendumEjari.mockReset(); });

function renderPanel(canRecord: boolean, onChanged = vi.fn()) {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseAddendaPanel leaseId="lease-1" addenda={[PENDING]} canRecordEjari={canRecord} onChanged={onChanged} />
        </NextIntlClientProvider>,
    );
    return onChanged;
}

describe("LeaseAddendaPanel", () => {
    it("flags an addendum with no Ejari as pending and lets finance record it", async () => {
        recordAddendumEjari.mockResolvedValue({ ...PENDING, ejariNumber: "EJ-1", ejariPending: false });
        const onChanged = renderPanel(true);
        expect(screen.getByText("ADD-27/1")).toBeInTheDocument();
        expect(screen.getByText("Ejari pending")).toBeInTheDocument();

        fireEvent.change(screen.getByTestId("addendum-ejari-a1"), { target: { value: "EJ-1" } });
        fireEvent.click(screen.getByTestId("addendum-ejari-save-a1"));
        await waitFor(() => expect(onChanged).toHaveBeenCalled());
        expect(recordAddendumEjari).toHaveBeenCalledWith("lease-1", "a1", "EJ-1");
    });

    it("shows no record control to a role that cannot post", () => {
        renderPanel(false);
        expect(screen.queryByTestId("addendum-ejari-a1")).not.toBeInTheDocument();
    });
});
```

`web/src/components/leases/LeaseAddendaPanel.tsx`:

```tsx
"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { fmtAmount } from "@/lib/api/ledger";
import { ApiError, leaseApi, type LeaseAddendum } from "@/lib/api/leasing";

/**
 * The addenda on a lease, oldest first. An addendum with no Ejari is flagged
 * pending — that flag is the whole of the Ejari follow-up — and finance can
 * record the number here once the variation is re-registered.
 */

const td = "px-3 py-2 text-xs";

type Props = {
    leaseId: string;
    addenda: LeaseAddendum[];
    canRecordEjari: boolean;
    onChanged: () => void;
};

export default function LeaseAddendaPanel({ leaseId, addenda, canRecordEjari, onChanged }: Props) {
    const t = useTranslations("Leasing");
    const [drafts, setDrafts] = useState<Record<string, string>>({});
    const [error, setError] = useState<string | null>(null);

    const save = async (a: LeaseAddendum) => {
        setError(null);
        try {
            await leaseApi.recordAddendumEjari(leaseId, a.id, (drafts[a.id] ?? "").trim());
            onChanged();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("recordEjariFailed"));
        }
    };

    return (
        <section data-testid="lease-addenda" className="bg-surface border border-border rounded-xl overflow-x-auto">
            <h3 className="px-3 py-2 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("addenda")}</h3>
            {addenda.length === 0 ? (
                <p className="px-3 pb-3 text-xs text-muted">{t("noAddenda")}</p>
            ) : (
                <table className="w-full min-w-[640px]">
                    <tbody>
                        {addenda.map(a => (
                            <tr key={a.id} className="border-t border-border">
                                <td className={`${td} font-semibold`}>{a.addendumNumber}</td>
                                <td className={td}>{a.effectiveFrom}</td>
                                <td className={td}>{a.reason ?? ""}</td>
                                <td className={`${td} text-end tabular-nums`}>{fmtAmount(a.value)}</td>
                                <td className={td}>{a.tcoEntryNumber}</td>
                                <td className={td}>
                                    {a.ejariPending ? (
                                        <span className="inline-flex items-center gap-2">
                                            <span className="px-2 py-0.5 rounded-full bg-warning/15 text-warning text-[10px] font-semibold">
                                                {t("ejariPending")}
                                            </span>
                                            {canRecordEjari && (
                                                <>
                                                    <input
                                                        data-testid={`addendum-ejari-${a.id}`}
                                                        aria-label={`${t("recordEjari")} ${a.addendumNumber}`}
                                                        className="bg-input border border-border rounded-md px-2 py-1 text-xs"
                                                        value={drafts[a.id] ?? ""}
                                                        onChange={e => setDrafts(d => ({ ...d, [a.id]: e.target.value }))}
                                                    />
                                                    <button
                                                        type="button"
                                                        data-testid={`addendum-ejari-save-${a.id}`}
                                                        disabled={!(drafts[a.id] ?? "").trim()}
                                                        onClick={() => save(a)}
                                                        className="px-2 py-1 rounded-md text-[11px] font-semibold border border-border disabled:opacity-50 cursor-pointer"
                                                    >
                                                        {t("recordEjari")}
                                                    </button>
                                                </>
                                            )}
                                        </span>
                                    ) : (
                                        a.ejariNumber
                                    )}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}
            {error && <p className="px-3 pb-3 text-[11px] text-error">{error}</p>}
        </section>
    );
}
```

Check that `bg-warning`/`text-warning` exist in the Tailwind theme (`grep -rn 'warning' web/src/app/globals.css | head -3`). If they don't, use the token the page already uses for amber status badges.

- [ ] **Step 7: Wire the lease page**

In `web/src/app/[locale]/dashboard/leases/[id]/page.tsx`:
- Imports: `AddChargeDialog`, `LeaseAddendaPanel`, and `type LeaseAddendum`. For the button icon use `PlusCircle` from `lucide-react`, added to the existing lucide import.
- State next to `extendOpen` (line 174):

```tsx
    const [addChargeOpen, setAddChargeOpen] = useState(false);
    const [addenda, setAddenda] = useState<LeaseAddendum[]>([]);
```

- Fetch the addenda wherever the page already loads the lease (`loadLease`). After the lease is set, add:

```tsx
        leaseApi.addenda(id).then(setAddenda).catch(() => setAddenda([]));
```

  `id` is whatever variable `loadLease` already uses for the lease id.
- Button directly after the Extend button block (`lease.status === "ACTIVE" && canExtend`, ~line 513-520), copying its classes:

```tsx
                        {lease.status === "ACTIVE" && canExtend && (
                            <button
                                onClick={() => setAddChargeOpen(true)}
                                data-testid="lease-add-charge"
                                className={/* the same className string as the Extend button */}
                            >
                                <PlusCircle size={14} /> {t("addCharge")}
                            </button>
                        )}
```

  Use the literal className string copied from the Extend button. Don't leave the comment in.
- Dialog after `<ExtendLeaseDialog … />`:

```tsx
            <AddChargeDialog
                open={addChargeOpen}
                lease={lease}
                chargeTypes={chargeTypes}
                onClose={() => setAddChargeOpen(false)}
                onAdded={async () => {
                    setAddChargeOpen(false);
                    await loadLease();
                }}
            />
```

- Panel, placed where the page renders its other lease sections below the header. Show it for any lease that has addenda, or when `canExtend`:

```tsx
            {(addenda.length > 0 || canExtend) && (
                <LeaseAddendaPanel leaseId={lease.id} addenda={addenda} canRecordEjari={canExtend} onChanged={loadLease} />
            )}
```

- [ ] **Step 8: Run the web suite, types and lint**

Run: `cd web && npx vitest run ; rc=$? ; echo rc=$rc`
Expected: `rc=0`, with the count above the 985 baseline (the new tests added).

Run: `cd web && npx tsc --noEmit ; rc1=$? ; npm run lint ; rc2=$? ; echo tsc=$rc1 lint=$rc2`
Expected: `tsc=0 lint=0`.

- [ ] **Step 9: Commit**

```bash
git add web/src/components/cheques/ChequeRowsEditor.tsx web/src/components/leases/ExtendLeaseDialog.tsx \
        web/src/components/leases/AddChargeDialog.tsx web/src/components/leases/LeaseAddendaPanel.tsx \
        web/src/components/leases/__tests__/AddChargeDialog.test.tsx \
        web/src/components/leases/__tests__/LeaseAddendaPanel.test.tsx \
        web/src/lib/api/leasing.ts "web/src/app/[locale]/dashboard/leases/[id]/page.tsx" \
        web/messages/en.json web/messages/ar.json
# plus AmendLinesDialog.test.tsx if Step 3 changed it
git commit -m "feat: Add charge on a posted lease, with an addenda panel and Ejari follow-up

Also rewrites the amend re-price message (#17) to point at Add charge
instead of a cheque-grid edit the UI never offered.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: Full verification, the tracking docs, and the spec amendment

**Files:**
- Modify: `tutorials/miftah-2y/gaps.md` (new rows for #15 and #17; keep every existing and `(orig)` row)
- Modify: `tutorials/miftah-2y/FIXPLAN.md`
- Modify: `docs/superpowers/specs/2026-09-23-lease-addendum-design.md`

- [ ] **Step 1: Full backend suite**

Run: `cd backend && ./gradlew test ; rc=$? ; echo rc=$rc`
Expected: `rc=0`. If anything fails, fix it before any doc says "fixed".

- [ ] **Step 2: Browser check on local**

Start the stack (`docker compose up -d`, or backend `./gradlew bootRun` on 8081 plus `cd web && npm run dev`). Sign in as the seeded tenant admin and open a posted lease with a cleared cheque:
1. Add charge → Parking Fee 6,000, effective mid-term, one 6,000 cheque, Ejari blank.
2. The addenda panel shows `ADD-yy/1` with **Ejari pending**. The register has the new row. The journals tab has a second TCO narrated `Addendum ADD-…`.
3. Record an Ejari number and confirm the badge clears.
4. Switch the locale to Arabic and confirm the dialog and panel render RTL with the new strings.

Take one screenshot of step 2 for the record.

- [ ] **Step 3: Tracking docs**

`gaps.md`: add a new row under each of #15 and #17 (don't edit or delete the existing rows):
- `| 15 | … | FIXED 2026-09-23 | Add charge posts a numbered addendum (ADD-yy/n): a further TCO for the new lines only and the cheques for it; the original TCO and cleared cheques are untouched. amendLines keeps its all-REGISTERED gate, which is correct. | LeaseVariationService, POST /leases/{id}/addenda |`
- `| 17 | … | FIXED 2026-09-23 (additions) | Contract value can now rise mid-term through Add charge; the amend dialog's re-price message points there. Reductions after money has cleared remain open — see the spec amendment. | AddChargeDialog, amendCannotReprice |`

`FIXPLAN.md`: mark #15/#17 done in their wave, and add a line naming the open item "settled downward correction (credit note + recognition re-plan)".

Spec: add a section `## Amendment 2026-09-23: downward corrections after settlement` stating the three verified facts from this plan's "Deviations" section (planLines positive-only, recognition skips `net <= 0`, `ck_lease_lines_net`). Also state that `ADVANCE_RENT` is the unearned-rent liability every TCO credits, so the approved "credit balance → ADVANCE_RENT" answer needs a fresh decision alongside the credit-note design.

- [ ] **Step 4: Commit**

```bash
git add tutorials/miftah-2y/gaps.md tutorials/miftah-2y/FIXPLAN.md docs/superpowers/specs/2026-09-23-lease-addendum-design.md
git commit -m "docs: mark #15/#17 additions fixed and record the open downward-correction design

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```
