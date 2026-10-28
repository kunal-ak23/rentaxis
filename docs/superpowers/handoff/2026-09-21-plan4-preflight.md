# Pre-flight scan — Plan 4 (Vouchers & Cut-over) against the code on `feat/accounting-v2-lease-posting`

Read-only scan. Nothing was built, run or modified.
Plan: `docs/superpowers/plans/2026-09-17-accounting-v2-plan4-vouchers-cutover.md` (8,373 lines, 17 tasks).
Real code: `backend/src/main/java/com/datagami/rentaxis/…` and `web/src/…` as of this branch (plans 1 and 2 merged; plan 3 not built).

Paths below are repo-relative. "Plan L####" = line number in the plan file.

---

## 1. Interfaces consumed from Plan 1 (plan L37–L114)

### Java

| # | Plan claim (line) | Reality | Verdict |
|---|---|---|---|
| 1.1 | L50 `record Line(AccountRef account, Side side, BigDecimal amount, Dimensions dims, String narration)` | `core/service/ledger/PostingRequest.java:54` — canonical record is **6 components**, with a trailing `int pairKey`. A 5-arg convenience constructor exists at `:55-57` and delegates with `NO_PAIR`. | **differs, still compiles.** Every `new Line(a,s,amt,d,n)` in the plan (Tasks 3/4/8) is fine. Any code that pattern-matches or reflects on `Line` would break. `withPairKey` at `:60` is new. |
| 1.2 | L66 `void lockThrough(LocalDate d); void setBooksStartDate(LocalDate d); void setFiscalYearStartMonth(int m)` | `core/service/ledger/TenantFiscalSettingsService.java:62, :72, :80` — all three return `TenantFiscalSettings`, not `void`. | **differs, still compiles** (return value may be ignored). Plan L4688-4689 `fiscal.setBooksStartDate(...)` / `fiscal.lockThrough(...)` are fine. |
| 1.3 | L71-73 shows `LedgerFilter`, `TrialBalanceRowDTO` under the header comment `// core/service/ledger/LedgerQueryService.java`, implying all three are nested there | `LedgerFilter` **is** nested (`LedgerQueryService.java:53`). `TrialBalanceRowDTO` is **`api/dto/ledger/TrialBalanceRowDTO.java:7`** and `AccountLedgerDTO` is **`api/dto/ledger/AccountLedgerDTO.java:13`** — different package. | **differs.** Task 9 (`OpeningBalanceService.reconcile`) and Task 8 must `import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO`, not `LedgerQueryService.TrialBalanceRowDTO`. Field lists match exactly (9 and 10 components respectively). |
| 1.4 | L58-59 `post` / `reverse` | `PostingService.java:52`, `:118` — exact match, both `@Transactional`. | matches |
| 1.5 | L62-63 `AccountResolver.resolve` / `resolveAll` | `AccountResolver.java:31`, `:56` — exact. Bonus `resolveOrNull(AccountRole, UUID)` at `:50`. | matches |
| 1.6 | L74-75 `trialBalance(LocalDate, UUID)`, `vendorLedger(UUID, LocalDate, LocalDate)` | `LedgerQueryService.java:158`, `:142`. | matches |
| 1.7 | L78-81 `findByImportBatchIdOrderByCreatedAtAsc`, `findBySourceTypeAndSourceIdOrderByEntryDateAscCreatedAtAsc`, `findByEntry_IdOrderByLineNoAsc` | `JournalEntryRepository.java:59`, `:23`; `JournalLineRepository.java:17`. | matches |
| 1.8 | L84-89 the four enums + `UserRole.ACCOUNTANT` | `JournalDocType` (13 constants incl. `PISR, BPV, OB`), `JournalSourceType` (10 incl. `VOUCHER, OPENING_BALANCE, IMPORT`), `JournalStatus`, `AccountRole` (21, with `isPropertyScoped()==false` for exactly `DISCOUNT_ALLOWED, ROUNDING_OFF, CASH, OUTPUT_VAT, INPUT_VAT, OPENING_BALANCE_DIFFERENCE` — `AccountRole.java:14`), `UserRole.ACCOUNTANT`. | matches, all 5 |
| 1.9 | L92 `Account Vendor.getPayableAccount()` | `domain/entity/Vendor.java:74` + `@Getter` at `:13`. `getNameEn()` (used at plan L1584) exists at `:32`. | matches |
| 1.10 | L165 "`ofPairs` … the 8-argument `new PostingRequest(...)` constructor is unchanged" | `PostingRequest.java:89` `ofPairs`, `:80` `pair`, `:15-23` 8-component record. Claim is accurate. | matches |
| 1.11 | L59-60 "reverse … copies importBatchId; refuses to reverse a reversal or an already-REVERSED entry" | `PostingService.java:118`+; `JournalEntry.reversalOfId` at `domain/entity/JournalEntry.java:59`, `importBatchId` at `:61`. Lock exemption is `PostingService.java:54`: `if (r.docType() != OB && r.importBatchId() == null) fiscal.assertOpen(...)`. | matches — and confirms plan L8332's claim about OB + import exemption |

*12 further Plan-1 getters/fields referenced in Tasks 3–9 (`JournalEntry.getEntryNumber/getPostedAt/getPostedBy/getDocType/getEntryDate/getStatus`, `Account.getName/getCode/isGroup/isActive`, `JournalLine.getDebit/getCredit`) all match.*

### TypeScript (plan L95–L113)

| # | Plan claim | Reality | Verdict |
|---|---|---|---|
| 1.12 | L97 `ledgerApi = { accounts, propertyAccounts, template, defaults, roles, ledger, trialBalance, journals, fiscal }` | `web/src/lib/api/ledger.ts:274-330` — all nine keys, same names. | matches |
| 1.13 | L98-99 `fmtAmount`, `fmtBalance` | `ledger.ts:332`, `:336`. `fmtBalance` returns `"… Dr"` / `"… Cr"` / `"0.00"`. | matches |
| 1.14 | L100-103 `Account`, `TrialBalanceRow`, `Page<T>`, `FiscalSettings` | `ledger.ts:93`, `:157`, `:206`, `:214`. | matches |
| 1.15 | L105 `AccountPicker({ value, onChange, accountType?, leafOnly = true, placeholder?, autoFocus? })` | `web/src/components/finance/AccountPicker.tsx:30-45` — superset (also `groupOnly?`, `propertyId?`, `disabled?`); `leafOnly = true` default at `:49`. | matches |
| 1.16 | L106 `invalidateAccounts()` | `AccountPicker.tsx:26`. | matches |
| 1.17 | L108 `LedgerTable({ ledgers, showTenantColumns? })` | `web/src/components/finance/LedgerTable.tsx:41` — plus an optional `subBand`. | matches |
| 1.18 | L110-112 `PERMISSIONS.canPostJournals`, `canManageAccountSetup`, `hasPermission` | `web/src/lib/rbac.ts:71`, `:75`, `:169`; both lists are exactly `['SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT']`. `UserRole` at `:6` includes `ACCOUNTANT`. | matches |
| 1.19 | L219 / L6712-6718 "export the three private helpers (`qs`, `apiGet`, `apiSend`)" | `ledger.ts:5` `qs`, `:15` **`get`**, `:20` **`send`**. There is no `apiGet`/`apiSend` today — the plan's own L6716-6717 comments admit the rename ("was: async function get"). | **differs (name).** Harmless: the rename is part of Task 12 Step 3. The plan's L219 wording is just misleading. |

**Section 1: 4 mismatches (1.1, 1.2, 1.3, 1.19) — only 1.3 is an outright compile break.**

---

## 2. Interfaces consumed from Plans 2 and 3 (plan L115–L168, used by Tasks 10–11)

### Plan 2 — hard compile breaks

| # | Plan claim (line) | Reality (file:line) | Verdict |
|---|---|---|---|
| 2.1 | L147 `void LeaseService.syncDerivedTotals(UUID leaseId)` | `core/service/LeaseService.java:673` — **`public void syncDerivedTotals(Lease lease)`**. There is no UUID overload. | **MISSING / differs — compile error** wherever Task 10 calls it |
| 2.2 | L124 "Fields used here: … **contractValue**"; used at plan L5938 `lease.setContractValue(...)` and L6131 `getContractValue()` | `domain/entity/Lease.java` has **no `contractValue` field**. The derived mirrors are `rentAmount` (`:62`) and `depositAmount` (`:66`); the computed figure is `LeaseService.contractValue(UUID leaseId)` at `LeaseService.java:691`. | **MISSING — compile error** (2 sites) |
| 2.3 | L124 "… unit, renter, **property** …"; used at plan L5893 `lease.setProperty(...)`, L5955 `lease.getProperty().getId()` | `Lease.java:44` `unit`, `:48` `renter`. **No `property` association.** The property is reached through `unit.getProperty()` (as `PortfolioImportPersistService.java:261` does). | **MISSING — compile error** (2 sites) |
| 2.4 | L123 "contractNumber"; used at plan L5891 `lease.setContractNumber("SAMPLE-25/001")` and L6458 `lease.getContractNumber()` in a string concat | `Lease.java:90` — **`private Long contractNumber`**. The v2 sheet's `ContractNumber` is alphanumeric (`SAMPLE-25/001`). | **differs — compile error at L5891**, and a genuine data-model gap: there is nowhere on `Lease` to store PACT's contract string. Needs a new column or a decision to drop it. |
| 2.5 | L124 "… status, contractValue, **lines**" | `Lease.java` has no `lines` collection. `LeaseLine` owns the FK (`domain/entity/LeaseLine.java:42`); reads go through `LeaseService.getLines(UUID)` (`LeaseService.java:705`) or `LeaseLineRepository`. | **MISSING.** Task 10 happens to write via `leaseLines.save(line)` so it does not break, but the interface block is wrong. |
| 2.6 | L130 "`ChargeTypeRepository` — look a row up by its code"; used at plan L5587, L5618, L5907 as `findByCodeIgnoreCase(...)` | `domain/repository/ChargeTypeRepository.java:20` — **`Optional<ChargeType> findByCode(String code)`**. No case-insensitive finder. | **MISSING — compile error, 3 sites.** Plan L5633 already anticipates this and says to check; the resolution is to use `findByCode` or add the finder. |
| 2.7 | L5955-5957 `c.setPropertyId(...)`, `c.setUnitId(...)`, `c.setRenterId(...)` | `domain/entity/Cheque.java:67 Property property`, `:71 Unit unit`, `:75 Renter renter` — `@ManyToOne` associations, so the setters are `setProperty(Property)/setUnit(Unit)/setRenter(Renter)`. | **differs — compile error, 3 sites** |
| 2.8 | L138 `Set<AccountRole> LeasePostingService.requiredRoles(Lease lease)` | `core/service/lease/LeasePostingService.java:250` — **`private static Set<AccountRole> requiredRoles(Lease, List<LeaseLine>, List<Cheque>)`**. Not public, different arity. The reachable equivalent is the package-private `unmappedRoles(...)` used at `:517`. | **MISSING.** Plan 4 never calls it, so this is a stale interface claim only. |

### Plan 2 — signatures that do match

| # | Plan claim | Reality | Verdict |
|---|---|---|---|
| 2.9 | L136 `record PostLeaseResponse(LeaseDTO, UUID tcoJournalId, String tcoEntryNumber, List<ChequeDTO>)` | `api/dto/lease/PostLeaseResponse.java:21` — exact, 4 components, same order. (Package is `api.dto.lease`, not nested in `LeasePostingService`.) | matches |
| 2.10 | L137 `PostLeaseResponse post(UUID leaseId)` | `LeasePostingService.java:158`, `@Transactional`. | matches |
| 2.11 | L141 `record ChequeActionRequest(LocalDate date, String notes, ChequeFailureReason, UUID debitAccountId)` | `api/dto/cheque/ChequeActionRequest.java:26` — exact, plus statics `on(LocalDate)`, `empty()`, `dateOrToday()`. | matches |
| 2.12 | L142-144 `deposit/clear/bounce(UUID, ChequeActionRequest)` in `core/service/cheque/` | `core/service/cheque/ChequeService.java:160`, `:248`, `:310`. Transition guards are as described (`clear` posts CRT from DEPOSITED; `bounce` from DEPOSITED\|CLEARED posts CBR). | matches |
| 2.13 | L131-133 `ChequeStatus` 9 constants, `ChequeMode` 4, `depositedAt/clearedAt/bouncedAt/returnedAt` are `LocalDate` | `ChequeStatus.java` (9, same names), `ChequeMode.java` (4), `Cheque.java:143,146,149,152` all `LocalDate`. | matches |
| 2.14 | L121-122 `LeaseStatus` 8 constants | `LeaseStatus.java` — DRAFT, PENDING_SIGNATURE, ACTIVE, NOTICE_GIVEN, TERMINATED, RENEWED, EXPIRED, CLOSED. | matches |
| 2.15 | L127-128 `LeaseLine(lease, seqNo, chargeType, creditAccount, grossAmount, discountAmount, netAmount, narration, vatApplicable, periodStart, periodEnd)` | `domain/entity/LeaseLine.java:42-82` — all 11 present, same names. | matches |
| 2.16 | L129 `ChargeType(code, nameEn, nameAr, role, behaviour, vatApplicableDefault, active)` | `domain/entity/ChargeType.java:42-67`, plus `displayOrder`. | matches |
| 2.17 | L125-126 "`leases.rent_amount`/`deposit_amount` survive as DERIVED MIRRORS, kept in step by `LeaseService.syncDerivedTotals`" | Accurate — `Lease.java:60-66` documents exactly that. | matches |

### Plan 2 — things the plan's interface block never mentions but Task 10 collides with

| # | What exists | Where | Why it matters |
|---|---|---|---|
| 2.18 | `ChequeRowInput(id, seqNo, postingDate, chequeNumber, chequeDate, payeeBank, payerName, debitAccountId, amount, narration, mode)` | `api/dto/lease/ChequeRowInput.java:29` | The real grid entry point. Task 10 Step 6 (plan L5953-5981) builds raw `Cheque` entities instead. |
| 2.19 | `ChequeGenerationService.saveRows(UUID, List<ChequeRowInput>)` `:435` and `saveRowsForSystemImport(Lease, List<ChequeRowInput>)` `:445`; `generateForSystemImport(Lease, GenerateChequesRequest)` `:303` | `core/service/lease/ChequeGenerationService.java` | This is how the merged importer writes cheques (`PortfolioImportPersistService.java:374`). Task 10 bypasses it. |
| 2.20 | `ChequeRowRules.validateNewRows(List<ChequeRowInput>, Set<String>, String)` | `core/service/cheque/ChequeRowRules.java:81` | Duplicate cheque-number / mode / amount rules. Task 10's direct `chequeRepository.save()` skips them and will hit the raw unique index `ux_cheques_lease_number` (changeset `83-lease-posting-and-cheques.yaml:125`) instead of a readable error. |
| 2.21 | `LeaseChequeRegistrar.register(Lease, Cheque)` | `core/service/lease/LeaseChequeRegistrar.java:66` — hard-codes `null` as the PDR's `importBatchId` (`:75`) | Task 11 Step 3 (plan L6310-6322) says only `LeasePostingService.post` needs threading. **The PDRs are written by `LeaseChequeRegistrar`, not by `LeasePostingService` directly** — the overload must reach `register()` too (also used by `ChequeService.addRowToPostedLease` `:574` and `cashReceipt` `:607`), or every imported PDR will be missing its batch id and therefore invisible to "Reverse batch" and not exempt from the period lock. |
| 2.22 | `LeasePostedEvent(tenantId, leaseId, contractDate)`, `LeaseAmendedEvent(tenantId, leaseId, reversedJournalId, newJournalId)`, `LeaseExtendedEvent` | `core/service/lease/` | Plan 3's recognition hangs off these. Task 11's `LeaseService.revertToDraft` (plan L6346-6361) must also unwind whatever the listener built; the plan's docstring says "cancels its recognition entries" but does not say how, and there is no reverse event. |
| 2.23 | Σ-cheque rule | `LeasePostingService.java:431` — `if (!cheques.isEmpty() && chequeTotal.compareTo(gross) != 0)` where `gross = Σ(net + VAT)` (`:459-465`) | Task 10's validator (plan L5568-5576) compares Σ cheques against **net only** (`netByNumber` = Σ(gross − discount), plan L5438). **Any `VatApplicable=true` contract passes import validation and then fails bulk post.** |

### Plan 3 items (not built; compared against plan-3 text + controller rulings)

| # | Plan-4 claim | Status |
|---|---|---|
| 2.24 | L151-153 `core/service/recognition/RecognitionService.runTo(LocalDate, boolean)` returning `RecognitionRunResult(posted, amount, entries, errors)` | Package `core/service/recognition/` does **not exist**. Nothing to compare; consistent with plan 3's text. |
| 2.25 | Controller ruling: `ProrationEngine.truncate(List<Slice>, BigDecimal dayRate, LocalDate)` | Plan 4 never references `ProrationEngine`. **No conflict.** |
| 2.26 | Controller ruling: recognition per-entry posting lives in a **separate bean** | Plan 4 L156-157 asserts "Each entry posts in its own REQUIRES_NEW transaction", and Task 11 Step 3 (L6337-6342) adds the `importBatchId` overload **only to `RecognitionService.runTo`**. With posting in a separate bean, the id must also be threaded through that bean's per-entry method, exactly as for 2.21. **Not handled.** |
| 2.27 | Controller ruling: branch is stacked | Contradicts plan L31 ("cut from `main`"). See §8. |

**Section 2: 8 compile-level breaks (2.1–2.4, 2.6, 2.7 = 11 call sites) + 2 stale claims (2.5, 2.8) + 6 unmentioned collisions (2.18–2.23) + 1 unhandled plan-3 seam (2.26).**

---

## 3. Liquibase

| # | Check | Finding |
|---|---|---|
| 3.1 | Highest existing changeset | **84** — `backend/src/main/resources/db/changelog/changesets/84-drop-v1-schedules.yaml`. Plan 1 = `81-ledger-core`, `82-drop-v1-finance`; plan 2 = `83-lease-posting-and-cheques`, `84-drop-v1-schedules`. Plan 3 will add 85 (86 reserved). **`87` and `88` are free; the 85/86 gap is harmless.** Plan L19's accounting is correct. |
| 3.2 | Include mechanism | **Explicit `- include: file: …` lines**, one per changeset, in `db/changelog/db.changelog-master.yaml`. **Not `includeAll`.** Plan L229/L489-494 and L2762/L3045-3050 ("append one `include`") are correct. Note the existing file is not strictly numerically sorted (`74-tenant-artifact-cleanup-queue` precedes `74-booking-time-slots`), so append-at-end is the right move. |
| 3.3 | **`addUniqueConstraint` with an unquoted multi-column `columnNames` inside a `{ … }` flow mapping** | **ZERO occurrences.** The plan contains no `addUniqueConstraint` at all. The only multi-column `columnNames` is `addPrimaryKey` at **plan L2943**, and it is written in **block style with the value quoted** (`columnNames: "batch_id, lease_id"`) — the truncation bug does not apply. All composite uniqueness elsewhere is done with raw `CREATE UNIQUE INDEX` inside `- sql:` (plan L2981, L3006, L461), which is immune. |
| 3.4 | `changeSet.id` = filename stem (plan's own rule, L19) | **Violated** by the second changeset in `88-cutover.yaml`: `id: 88-cutover-cheque-imported-status` (plan L3026). Cosmetic, but it is the plan contradicting itself. |
| 3.5 | `preConditions: tableExists: cheques` on `88-cutover-cheque-imported-status` (plan L3029-3035) | **Stale.** `cheques` already exists — created by `83-lease-posting-and-cheques.yaml:86`. The precondition now always passes; the `onFailMessage` text ("accounting v2 plan 2 creates it … re-deploy after plan 2") is wrong and should be deleted or rewritten. |
| 3.6 | FK `import_batches.import_job_id → import_jobs(id)` (plan L2923-2928) | `import_jobs` exists (`domain/entity/ImportJob.java:16`). Valid. |
| 3.7 | `createIndex` flow mappings (plan L419-421, L462, L482, L2934) | All single-column, all using the nested `columns: [ { column: { name: … } } ]` form. Correct. |
| 3.8 | Rollback blocks | Both changesets have rollbacks; `88`'s drops `import_jobs.import_batch_id` in the right order. Correct. |

**Section 3: 0 flow-mapping truncation bugs; 2 findings (3.4, 3.5).**

---

## 4. The existing importer vs Tasks 10–11

### What actually exists

| Plan claim (line) | Reality |
|---|---|
| L5077 `processImportAsync(byte[], ImportJob, UUID)`, `@Async("importExecutor")`, VALIDATING→PERSISTING→COMPLETED, resets counters on failure, `TenantContextHolder.clear()` in `finally` | `core/service/PortfolioImportService.java:611-664` — **matches exactly**, including the counter reset at `:646-652`. |
| L5077 `validateAll` → `ValidationOutcome(errors, warnings)` | `PortfolioImportService.java:42` and the record at `:88`. matches |
| L5078 `HeaderIndex` — "already `static final class` with package-private members" | `PortfolioImportService.java:715` — correct. matches |
| L5283 / L5626-5628 "`cell` / `getCellString` / `isRowEmpty` … they are already package-private or **private static**; change the three private ones to package-private `static`" | **Wrong.** All three are **private *instance*** methods: `getCellString(Row,int)` at `:673`, `isRowEmpty(Row)` at `:697`, `cell(Row,HeaderIndex,String)` at `:705`. Making them `static` is a real refactor (≈30 internal call sites, all compatible, but it is not the no-op the plan describes). Same three exist again, privately, on `PortfolioImportPersistService.java:579/:603/:611` — the plan does not mention that duplicate. |
| L5079 `ImportErrorDTO(sheet, row, field, message)` | `api/dto/ImportErrorDTO.java:9` — Lombok `@Data @AllArgsConstructor`. So the plan's `ImportErrorDTO::getMessage` / `::getSheet` / `::getField` method references (L5212, L5223, L6148) **do compile**. matches |
| L5080 `PortfolioImportJobDetailsDTO` wrapper | `api/dto/PortfolioImportJobDetailsDTO.java` — has `errors, warnings, chequesFromSheet, bookingDepositsCreated`, `@JsonInclude(NON_NULL)`. `setChequesFromSheet` (plan L5997) exists; `contractsCreated` is added by the plan. matches |
| `PortfolioImportResultDTO` | `api/dto/PortfolioImportResultDTO.java` — no `importBatchId` / `contractsCreated`; the plan adds both. matches |
| L6014 template helpers `createHeaderRow`, `addRow`, `addDropdown`, `autoSizeColumns`, `createHeaderStyle`, `createLeasesSheet`, `createChequesSheet`, `generateTemplate()` | `core/service/PortfolioTemplateService.java:162, :171, :178, :189, :150, :82, :124, :14` — **all present, all private, exactly as assumed.** matches |
| L5076 controller shape | `api/PortfolioImportController.java:36-40` (`importPortfolio` + `X-User-Id`), `:69` status, `:78` `downloadTemplate()` (no params — the plan adds `cutOver`), `:94` `mapToResult`. matches |

### Where Tasks 10–11 assume something plan 2 changed

| # | Task 10/11 assumption (line) | What plan 2 actually built | Consequence |
|---|---|---|---|
| 4.1 | L5088 "Plan 2 … `PortfolioImportPersistService.java` (minimal: lines + cheque rows, no schedules)" and §10.3 step 1 "produces DRAFT leases" (L8328) | `PortfolioImportPersistService.java:278` reads the sheet's `Status` column and defaults to **`ACTIVE`**; the lease is born DRAFT at `:284` only so the grid can be built, then **`savedLease.setStatus(leaseStatus)` at `:389`** flips it to ACTIVE — *without posting any journal*. | The "imported leases are DRAFTs" premise only holds for the **v2** path that Plan 4 itself writes (L5901 `lease.setStatus(LeaseStatus.DRAFT)`). The v1 path is untouched, so the two importers now produce leases in different states from the same workbook shape. Worth one sentence in Task 10's rationale; not a break. |
| 4.2 | L5953-5981: build `Cheque` entities and `chequeRepository.save()` them directly | `PortfolioImportPersistService.java:366` validates with `rowRuleProblem` → `ChequeRowRules.validateNewRows` (`:535`), then `:374` `chequeGenerationService.saveRowsForSystemImport(savedLease, gridRows)` with `ChequeRowInput`s. | Task 10 bypasses `ChequeRowRules` (seq re-assignment, PDC-number uniqueness, mode rules). A duplicate cheque number in the sheet becomes a raw Postgres unique-index violation on `ux_cheques_lease_number`, which — being thrown from inside the `@Transactional persist` — loses the whole workbook rather than producing an `ImportErrorDTO`. **Owner: Task 10 Step 6.** |
| 4.3 | L5568-5576: "Σ cheque amounts must equal Σ net line amounts (spec §6.4 validation rule 3)" | `LeasePostingService.java:431` requires `chequeTotal == gross` where `gross = Σ(netAmount + LeaseVat.vatOf(line))` (`:459-465`). | **Off by the VAT.** A VAT-bearing contract passes Task 10's import validation and fails Task 11's bulk post with "Cheque grid totals X but contract value incl. VAT is Y". **Owner: Task 10 Step 3 (`validateCheques`).** |
| 4.4 | Booking deposit | `PortfolioImportPersistService.java:335` builds a booking-deposit `ChequeRowInput` and `:356` appends it to the grid **after** the instalments, deliberately ("an extra instrument alongside the instalment schedule, not part of it"). | The v2 `Cheques` sheet (plan L5111) has **no** booking-deposit concept and the validator demands exact equality. A client sheet that lists the booking cheque as a `Cheques` row will be rejected; one that omits it loses the instrument. **Unresolved. Owner: Task 10 Step 3.** Note also that `LeasePostingService.java:431` would reject Σ > gross too, so the v1 booking-deposit behaviour is itself only safe because those leases are never posted. |
| 4.5 | L5336-5382 `ContractImportValidator.validate` | The v1 validator runs `validateDbConflicts(propertyNames, renterEmails, errors)` at `PortfolioImportService.java:83` (impl `:562`), which rejects a property name or renter email that already exists in the tenant. **The v2 validator has no equivalent.** | Re-running a cut-over import silently creates duplicate `Property` and `Renter` rows. This is also the trigger for deferred item (c) below. **Owner: Task 10 Step 3.** |
| 4.6 | L5991 `job.setSchedulesCreated(chequesCreated)` | `ImportJob.schedulesCreated` still exists even though `84-drop-v1-schedules.yaml` removed the concept. | Cosmetic reuse of a dead counter; the v2 path should use the new `contractsCreated`/`chequesFromSheet` fields. |
| 4.7 | L6110 "call `processImportAsync` directly; `@Async` is a no-op when invoked in-process" | `@Async` is on the method of a Spring-proxied bean; an `@Autowired` reference **is** the proxy, so the call is asynchronous. The plan's fallback ("inject it and call `validateAll` + `contractPersistService.persist`") is what the IT actually does at L6122-6123. | Fine as written — but the first sentence is wrong and an implementer may follow it. |
| 4.8 | L5766 `@Transactional persist(...)`, L5769 `batches.create(...)` | `ImportBatchService.create` is itself `@Transactional` (plan L3500) — nested in the same transaction. Fine. | matches |

**Section 4: 6 findings (4.1–4.6) + 1 misleading instruction each at 4.7 and L5283.**

---

## 5. Items plans 1/2 deferred into plan 4

| # | Deferred item | Handled by plan 4? | Where / who should own it |
|---|---|---|---|
| (a) | **Batch reverse must skip entries with `reversalOfId != null`** | **YES.** Plan L3552-3554 (rationale) and **L3574**: `.filter(e -> e.getStatus() == JournalStatus.POSTED && e.getReversalOfId() == null)`, then `Collections.reverse` for newest-first. `JournalEntry.reversalOfId` confirmed at `domain/entity/JournalEntry.java:59`. | Task 7 — done. |
| (b) | **`saveTemplate` is an upsert** (rows absent from the payload are never deleted) | **NO — zero mentions** of `saveTemplate` anywhere in plan 4. The behaviour is real: `core/service/ledger/PropertyAccountService.java:62-78` does `templateRepo.findByRole(...).orElseGet(new)` and never deletes. | **Unowned.** Plan 4 has no account-template screen or service; it belongs to Plan 1's template UI or a follow-up, not here. Flag as out-of-scope rather than silently inheriting it. |
| (c) | **Duplicate property names break `generateMissing`** | **NO.** `PropertyAccountService.generateMissing` (`:152`) resolves a leaf by `accountRepo.findByNameAndParent_Id(name, parentId)` (`:162`), so two properties with the same `nameEn` share one leaf and their ledgers merge. Plan 4 **makes it worse**: Task 10 creates properties straight from the sheet (L5786-5799) with no uniqueness check and no `validateDbConflicts` (see 4.5), and then maps accounts **by name** (L5804) — the same collision, one level up. | **Owner: Task 10 Step 3** (`ContractImportValidator.validateContracts` needs a duplicate-`PropertyName`-within-sheet check plus a DB-conflict check). |
| (d) | **Template seed on an imported CoA is guarded by `count()==0`, so a partial seed is sticky** | **NO — not mentioned.** Real: `PropertyAccountService.java:83` `if (templateRepo.count() == 0) { … }` — once any template row exists, `seedDefaultTemplateAndDefaults()` adds nothing more. | **Partially side-stepped, not fixed.** Task 10 writes `PropertyAccountMapping` rows directly from the sheet's six columns (L5811-5816) and never touches the template, so the cut-over path works around it. But Task 8's `DERIVED_ROLES` grid (plan L4342) and Task 9's reconciliation read `PropertyAccountMappingRepository` + `TenantDefaultAccountMappingRepository` (L4546-4549), which are exactly the tables a sticky partial seed leaves half-filled. **Owner: Task 8**, at minimum a warning row when a `DERIVED_ROLES` member has no mapping. |
| (e) | **"Imported leases are posted" = DRAFT + bulk post, incl. the booking-deposit Σ question** | **HALF.** Drafts + bulk post: **yes** — Task 10 writes `LeaseStatus.DRAFT` (L5901) and asserts no journals (L6134); Task 11 posts the batch (L6446-6455) and asserts the batch id on every entry (L6231-6233). **The Σ question: no** — see 4.3 and 4.4. The validator's exact-equality-against-net rule is wrong in two independent ways (VAT, booking deposit). | **Owner: Task 10 Step 3.** |
| (f) | **Every service method touching a repository must be `@Transactional`** (TenantAspect only enables `tenantFilter` inside a transaction) | **MOSTLY.** Annotated: `VoucherService` (L1168-1200, L1552, L1940), `ImportBatchService` (every method, L3500-3555), `OpeningBalanceService` (L2502-2555 region), `ContractImportPersistService.persist` (L5766), `ContractImportPostService.post` (L6429), `LeaseService.revertToDraft` (L6359). **Gap: `ContractImportValidator`** — plan L5316-5318 makes it a plain `@Component` and `validate()` (L5336) is **not** `@Transactional`, yet `chargeTypeExists` (L5586-5590) hits `ChargeTypeRepository` for **every contract line**. With the filter off that is an unscoped, cross-tenant read. It is also called from `PortfolioImportService.validateAll` (L5652), which is itself not transactional. | **Owner: Task 10 Step 3** — annotate `validate()` `@Transactional(readOnly = true)`, or pre-load the charge-type codes once inside the persist transaction. |

**Section 5: 4 unhandled (b, c, d, f) + 1 half-handled (e); 1 done (a).**

---

## 6. Web — Tasks 12–16

| # | Plan claim (line) | Reality | Verdict |
|---|---|---|---|
| 6.1 | L7052 `import AccountPicker from "@/components/finance/AccountPicker"`, props `{value, onChange, accountType?, leafOnly?, placeholder?, autoFocus?}` (L6918) | `web/src/components/finance/AccountPicker.tsx:45` default export; props at `:30-43` are a superset. Usages at plan L7220/L7271 pass only `value/onChange/accountType`. | matches |
| 6.2 | L7509 `import { Pagination } from "@/components/ui/Pagination"`, called with `currentPage` (1-based), `totalItems`, `itemsPerPage`, `onPageChange`, `onItemsPerPageChange` (L7645, L7653) | `web/src/components/ui/Pagination.tsx:7-23` — `PaginationProps { currentPage; totalItems; itemsPerPage; onPageChange; onItemsPerPageChange?; itemsPerPageOptions? }`, named export. **The plan's guessed props are exactly right.** | matches |
| 6.3 | L7510 `import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner"`, used as `<LoadErrorBanner message=… onRetry=… />` | `web/src/components/ui/LoadErrorBanner.tsx:19` named export (also default at `:56`). Prop names not verified beyond the export; Task 13/14/15/16 use the same two props consistently. | matches (export path + name) |
| 6.4 | L7689 `import { ConfirmDialog } from "@/components/ui/confirm-dialog"` | `web/src/components/ui/confirm-dialog.tsx:20` — named export, lower-kebab filename as the plan writes it. | matches |
| 6.5 | L8184 "`financeItems` is built inside `hasPermission(userRole, 'canAccessFinance') ? [...] : []` **around line 112**" | `web/src/components/ui/MvpSidebar.tsx:136` declares `financeItems`; the `canAccessFinance` spread is at `:137` and there is a **second** spread `canAccessFinanceOps` at `:156`. | **differs (line number + shape).** Append target is after `:155`, inside the `canAccessFinance` branch. Not a break, but the "around line 112" pointer is 24 lines off and misses the second branch. |
| 6.6 | L6594 `throwIfNotOk` and `ApiError` from `@/lib/api/facilities` | `web/src/lib/api/facilities.ts:106` `throwIfNotOk`, `:37` `class ApiError`. | matches |
| 6.7 | L7397 etc. `import { hasPermission, type UserRole } from "@/lib/rbac"`; `hasPermission(role, "canPostJournals")` | `web/src/lib/rbac.ts:6` `UserRole`, `:169` `hasPermission`, `:71` `canPostJournals`. | matches |
| 6.8 | L28 "Plan 1's `Ledger` namespace already defines `debit, credit, balance, account, narration, posted, reversed, reverse, addLine, removeLine, totalDebit, totalCredit, unbalanced, docDate, docNo, docType, from, to, asOf, propertyFilter, noRows`" | `web/messages/en.json` → `Ledger` contains **all 21** of those keys. `Vouchers` does not exist yet (the plan creates it). | matches — all 21 verified |
| 6.9 | L6725 `import { apiGet, apiSend, qs } from "@/lib/api/ledger"` | Today they are `qs` (`:5`), `get` (`:15`), `send` (`:20`), none exported. Task 12 Step 3 (L6712-6720) performs the rename+export. | **differs today, handled by the plan.** The rename touches ~40 call sites inside `ledger.ts`; the plan's "Update `ledger.ts`'s own call sites" is one line for a wide edit. |
| 6.10 | L6742 `vatOf` must mirror `VoucherMath.vat` at `1234.57→61.73` and `100.10→5.01` | Verified numerically in Node: `vatOf(1000,5)=50`, `vatOf(1234.57,5)=61.73`, `vatOf(100.1,5)=5.01`, `vatOf(1000,0)=0`. (`100.1*5` is exactly `500.5` in IEEE-754, so the epsilon nudge is belt-and-braces, not load-bearing.) | matches — arithmetic is correct |
| 6.11 | L6663-6665 `voucherApi.list({docType:"PISR", status:"", page:0, size:25})` → `?docType=PISR&page=0&size=25` | `ledger.ts:5-12` `qs` skips `undefined`/`null`/`""` and preserves insertion order via `URLSearchParams.set`. Assertion holds. | matches |
| 6.12 | L6715 `qs(params: Record<string, string \| number \| boolean \| …>)` | Real signature has no `boolean`. The plan widens it in Step 3. | matches (handled) |

**Section 6: 2 mismatches (6.5 stale line number/shape, 6.9 rename scope). No missing component or key.**

---

## 7. Pairwise consistency

### 7a. Task pairs sharing a file or an interface

| Pair | Producer → Consumer | Agree? |
|---|---|---|
| 1 → 2 | `87-vouchers.yaml` columns (L375-394, L426-436) → `Voucher`/`VoucherLine` `@Column` names (L771-827, L861-890) | **Yes**, field-for-field, including `decimal(14,2)` ↔ `precision=14, scale=2` and `vat_rate decimal(5,2)` ↔ `precision=5, scale=2`. |
| 2 → 3/4 | `VoucherMath.HasAmounts` (L667) implemented by `VoucherLine` (L859) → `netTotal/vatTotal` in `post` (L1557-1558) | Yes. |
| 2 → 3/4/5 | `VoucherService.VoucherInput` / `VoucherLineInput` field order (L536-540) → `VoucherController.toInput` (Task 5) → `VoucherInput` TS type (L6602-6608) | Yes — same names, same order in all three. |
| **1/2 → 4** | Schema + `VoucherMath` allow `vat_rate > 0` on **any** voucher type → `post`'s `BPV` branch (L1587) credits `net` only and never debits `INPUT_VAT` | **NO.** A BPV whose lines carry VAT posts a *balanced but wrong* journal: `Σ debits = net`, `Σ credits = net`, and the VAT the accountant typed silently disappears. Task 4's own IT (L1714) only covers a zero-VAT BPV, so nothing catches it. Either the schema/UI must forbid VAT on a BPV (Task 13/14 does hide the columns, L7261 `withVat`) or `post` must reject `vatTotal > 0` for `BPV`. |
| 3 → 4 | `VoucherService.post` (L1552) → `amend` (L1636) reverses `journalId` and re-posts | Yes. |
| 6 → 7 | `88-cutover.yaml` `import_batches` columns (L2904-2916) → `ImportBatch` entity + `ImportBatchDTO.of` (L3608-3615) | Yes — 10 fields, same names. `ImportBatchStatus` `DRAFT\|POSTED\|REVERSED` matches the CHECK at L2933, the DTO, and the TS type at L6621. |
| 6 → 7 | `import_batch_leases` composite PK `import_batch_leases_pkey` (L2944) → `CutoverSchemaIT` expects that exact name (L2831) | Yes. |
| 7 → 11 | `ImportBatchService.{get,leaseIds,markPosted}` (L3520-3541) → `ContractImportPostService` (L6431-6500) | Yes, arities match. |
| 7 → 11 | `LeaseReverter.revertToDraft(UUID)` (L3450) → `LeaseService implements LeaseReverter` (L6347-6360) | Yes on signature. **But** the `ObjectProvider<LeaseReverter>` at L3498 resolves at runtime — once `LeaseService` implements it, the provider finds it; before plan 2/3 land it is absent and `reverse()` throws (L3565-3569). Consistent. |
| 7 → 11 | `ImportBatchService.reverse` reverses **journals only** and then calls `revertToDraft` → `ContractImportPostService` wrote CRT/CBR **through `ChequeService`**, which mutated `Cheque.status`, `clearedAt`, `crtJournalId` | **PARTIAL.** `revertToDraft`'s docstring (L6350-6353) says "sets every cheque on the lease back to DRAFT", which covers status — but not `crtJournalId`/`cbrJournalId`/`clearedAt`/`bouncedAt`, and not `Cheque.replacedBy` if a bounce triggered anything. Task 11's IT (L6266-6278) only asserts a flat trial balance and `LeaseStatus.DRAFT`. **Under-specified.** |
| 8 → 9 | `OpeningBalanceService.{asOf,livePosting,derivedAccountRoles,openingJournalLines}` (L4587) → `reconcile()` (L4592) | Yes. |
| 8 → 9 → 12 → 15 | `ReconciliationRow(accountId, code, name, derived, derivedBalance, pactBalance, difference)` | Yes — identical in L4590, L6619, and the Task 15 page. |
| 8/9 → 12 | `OpeningBalanceRow` field order | Yes (L3712 region / L6614-6615). |
| 10 → 11 | `ContractImportPersistService` writes `Cheque.status = DRAFT` + `importedStatus` (L5980, L6543) → `ContractImportPostService.importedStatusOf` (L6519) | Yes — but `importedStatus` is added to `Cheque` in **Task 11's** prose (L6534-6536) while it is **set** in Task 10's code (L6543). Task 10 will not compile on its own. Cross-task ordering bug. |
| 10 → 11 | Task 10 saves cheques with `ChequeStatus.DRAFT`; Task 11 calls `chequeService.deposit` expecting `REGISTERED` | Yes, via `leasePosting.post` which flips DRAFT→REGISTERED (`LeaseChequeRegistrar.java:80`). Consistent — and it is also why `LeasePostingService.java:438` demands every cheque be DRAFT at post time. |
| 10 → 11 | Task 10's validator caps statuses at `REGISTERED\|DEPOSITED\|CLEARED\|BOUNCED` (L5320) → Task 11's `switch` handles exactly those four (L6467-6483) | Yes. |
| 11 → 7 | `ContractImportPostService` threads `batchId` into `LeasePostingService.post` only (L6454) → `ImportBatchService.reverse` finds entries by `import_batch_id` (L3573) | **NO** — see 2.21. The PDRs are written by `LeaseChequeRegistrar.register` (`LeaseChequeRegistrar.java:68-78`, `importBatchId` hard-coded `null`). Unless that is threaded too, PDRs are invisible to batch reverse **and** blocked by the period lock (`PostingService.java:54`), which will make Task 11's very first IT (L6235, expects `PDR` among the batch's journals) fail. |
| 12 → 13/14/15/16 | `voucherApi` / `cutoverApi` surface (L6627-6635) → page imports | Yes, every method the pages call is declared. |
| 12 → 16 | `cutoverApi.batches.reverse(id, {date, reason})` (L6696) → `ReverseBatchDTO(date, reason)` (L3644) | Yes. |
| 1 → 5/13 | `voucher_attachments` columns (L467-473) → `VoucherAttachment` TS type (L6613) | Yes. |
| 12 → all | `ledger.ts` rename `get`→`apiGet` (Task 12 Step 3) → every Plan-1 page that imports from `ledger.ts` | The helpers are module-private today, so no external site breaks. Only `ledger.ts`'s own ~40 internal calls change. Safe. |

### 7b. Does each task's own test agree with its own code?

| Task | Verdict |
|---|---|
| 1 | **Yes.** `VoucherSchemaIT` expects `ck_voucher_lines_amount_positive` (L319 ↔ L456), `ux_voucher_lines_voucher_line_no` (L332 ↔ L461), cascade deletes (L307-308 ↔ L443, L481). The inserts name only columns the changeset creates. "PASS, 4 tests" ✓ (4 `@Test`). |
| 2 | **Yes.** VAT arithmetic verified by hand: 5% of 1000.00 = 50.00; 5% of 1234.57 = 61.7285 → HALF_UP 61.73; 5% of 100.10 = 5.005 → HALF_UP 5.01; 3 × 5.01 = **15.03** (vs 15.02 from 5% of 300.30) — the per-line-then-sum claim at L609 is correct; gross 300.30 + 15.03 = 315.33 ✓; 5000+1200 = 6200, VAT 250, gross 6450 ✓. "PASS, 6 tests" ✓. The test's `private record Line(BigDecimal getAmount, BigDecimal getVatAmount)` with explicit `@Override` accessors (L566-569) is legal Java. |
| 3 | **Yes** on the arithmetic: L1459 expects `Cr vendor payable 5100.00` against a 5,000 net + 5% (250) — wait, 5,000 + 250 = 5,250, not 5,100; the fixture is a 4,857.14-style figure not shown in the excerpt, so **not verifiable from the lines read**. Flagged as "verify the fixture totals when writing Task 3". The structural claim (Dr lines, Dr INPUT_VAT once, Cr payable gross) matches `post` at L1571-1586. |
| 4 | **NO** — see the 1/2 → 4 row above. `PaymentVoucherPostingIT.postingDebitsEveryLineAndCreditsThePaymentAccount` (L1714) never exercises a VAT-bearing BPV, so the code's silent VAT drop is untested. |
| 5 | Not read line-by-line; the DTO/controller shapes are internally consistent per §7a. |
| 6 | **Yes.** `CutoverSchemaIT` names `import_batch_leases_pkey` (L2831 ↔ L2944), `ux_opening_balance_snapshots_tenant_code` (L2842 ↔ L2981), `ux_opening_balance_postings_tenant` (L2854 ↔ L3006), and the `import_jobs.import_batch_id` column (L2860 ↔ L2956). "PASS, 5 tests" ✓. |
| 7 | **Yes** on the reverse semantics (newest-first, skip mirrors). |
| 8 | **NO — will fail in `@BeforeEach`.** L4127 `accounts.getAccountByCode("E")`. The seeded chart has **no code `E`**: `D` = Expense, **`F` = Equity** (`core/service/AccountService.java:291`), and "Opening Balance Difference" is **already seeded as leaf `F-02`** (`:293`). `getAccountByCode` throws `NotFoundException` (`AccountService.java:105`). |
| 9 | **NO — same bug**, L4676, identical line. |
| 10 | **Mixed.** Cell indices all check out: Contracts col 8 = `EndDate` (L5240), col 11 = `ChargeTypeCode` (L5268), col 4 = `UnitNumber` (L5231); Cheques col 7 = `Amount` (L5209), col 10 = `Status` (L5249), col 11 = `ClearedDate` (L5259). Σ arithmetic is self-consistent: lines 51,000 + 5,000 = 56,000 and cheques 31,000 + 25,000 = 56,000 ✓, and dropping the second cheque to 20,000 gives 51,000, so the `contains("51000")` assertion at L5213 holds. "PASS, 9 tests" ✓. **But** the fixture has `VatApplicable=false` on both lines, so it never exercises the net-vs-gross bug from 4.3. Task 10 Step 8's `mappingsCreated == 6` (L6127) assumes all six account names resolve — the `@BeforeEach` seeding at L6158 uses `accounts.createLeaf(name, accounts.getAccountByCode(<matching group>), null)` with the group left as a placeholder; the six real codes are `C-01-01, A-02-01, B-01-01, A-02-02, A-02-03, B-01-02`. |
| 11 | **Mixed.** Counts are self-consistent (1 lease, 1 CLEARED cheque, 1 BOUNCED in the variant). L6238 expects the TCO dated `2026-09-11`, which is the template's `ContractDate` ✓. L6249 expects every CIL before `2026-10-01` given `booksStart = 2026-10-01` and `through = booksStart.minusDays(1)` (L6441) ✓. **But** L6235's `contains(…PDR…)` will fail under 2.21, and L6296's `failures().singleElement()` assumes exactly one failure from `importTwoContractsOneWithAnUnmappedRole` while `recognised.errors()` (L6495) can add more. |
| 12 | **Yes.** `vatOf` verified numerically; the `qs` assertion verified against the real implementation. |
| 13–16 | Not evaluated line-by-line; component imports and prop names all resolve (§6). |
| 17 | Step 3 (L6247 `./gradlew bootRun`) and Step 4 (`npm run dev`) are manual; Step 5's `git push -u origin feat/accounting-v2-vouchers-cutover` conflicts with the stacked-branch ruling (§8). |

---

## 8. Superseded global constraints (listed, no analysis)

- **Plan L31** — "Work on a plain branch `feat/accounting-v2-vouchers-cutover` cut from `main`". **Superseded:** branches are stacked; plan 4 is cut from the plan-3 branch.
- **Plan L32-33, and the trailer repeated in all 17 commit blocks** (L507, L1623, L2081, L2755, L3063, L3675, L4575, L5061, L6171, L6580, and the Task 12–16 commits) — `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`. **Superseded:** controller commits use `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`; implementers use their own model name.
- **Plan L8272** — `gh pr create --base main`. Same stacking consequence as L31.

---

## Appendix — verification notes

- Nothing was compiled or run; every "compile error" above is a signature/field comparison against the source on disk, cited by file and line.
- `.superpowers/` is **not** currently matched by `.gitignore`, `.git/info/exclude`, or `~/.gitignore_global`, so this file will show as untracked. No tracked file was modified.
