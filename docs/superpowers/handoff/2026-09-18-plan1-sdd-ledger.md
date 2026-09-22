# SDD ledger — plan: docs/superpowers/plans/2026-09-17-accounting-v2-plan1-ledger-core.md
Spec: docs/superpowers/specs/2026-09-17-accounting-v2-design.md (read). Branch: feat/accounting-v2-ledger-core (cut from docs/accounting-v2-design @ 34fcbb1c). Plain branch, no worktree (user preference).
Models: implementers = opus (user directive: Opus for build); reviewers = opus for ledger-critical tasks (1-2, 3, 5, 8, 15), sonnet otherwise; final review = opus.

## Pre-flight scan (2026-09-17)
| Pair / task | Produces vs consumes | Finding | Ruling |
|---|---|---|---|
| T1 ↔ T2 | T1 drops accounts.parent_code/hierarchy_level; T2 removes the entity fields | @SpringBootTest boots fail between the two (schema validation) — plan says commit together | Ruling: batch T1+T2 into one dispatch, one review — cost if wrong: one larger review diff |
| T1/T3/T5/T8 ↔ Addendum A | contra_account_id column, JournalLine.contraAccount, PostingRequest.ofPairs/Pair, LedgerQueryService particular | Addendum written after tasks; briefs extracted by task-brief omit it | Ruling: addendum-a.md handed to T1, T3, T5, T8 implementers as part of their requirements — cost if wrong: none |
| T2 ↔ T15 | T2 drops AccountMappingService from AccountService ctor; class survives until T15 | consistent | — |
| T2 UserRole.ACCOUNTANT ↔ LeaseAccessPolicy | policy fails closed for unknown roles | Plan 2 adds ACCOUNTANT to the policy; in plan 1 no lease endpoint is exercised by ACCOUNTANT | Ruling: leave to plan 2 — cost if wrong: an ACCOUNTANT cannot read leases until plan 2 |
| T3 JournalEntry.lines cascade PERSIST ↔ T5 `lines.saveAll(saved.getLines())` | double persist path | harmless merge no-op, implementer may drop the saveAll | Ruling: either is acceptable; reviewer judges — cost: none |
| T5 in-memory balance check ↔ T1 deferred trigger | both enforce | intended defence in depth | — |
| T6 ↔ T2 AccountController.seed | T6 adds propertyAccountService.seedDefaultTemplateAndDefaults() call in the controller | consistent; T2 must not seed mappings | — |
| T6 PropertyService → PropertyAccountService → AccountService | no cycle | — | — |
| T7 VendorService/BankAccountService method names | brief tells implementer to confirm real names | — | — |
| T8 old FinancialTransactionController `/ledger/vendor/{id}` mapping | ambiguous mapping until T15 | T8 says delete the old method now | Ruling: T8 removes that one mapping method — cost: none |
| T9 test helper `as()` | awkward RestClient cast in plan | brief says copy UserControllerRoleAuthorizationTest style | — |
| T12 property detail page path | plan says confirm | — | — |
| T15 SettlementService.postDepositSettlement | real name is postDepositReleaseEntries, called from finalizeSettlement AND createSettlement | plan text wrong on the name | Ruling: T15 dispatch names postDepositReleaseEntries and both call sites — cost if wrong: compile error, caught immediately |
| Rubric check | T1 tests assert real DB behaviour; no assertion-free tests; no verbatim duplicated logic blocks mandated | clean | — |


## Execution
Task 1+2: implemented by opus agent ae3aafbee7150db7f — commits 7c9619b6, 7098671b; full suite 1265/0. Concerns: web accounts page broken until T12 (expected, in-branch); brief contradictions resolved by implementer (landlord_org table name, trigger RAISE format, getTree roots-only); ACCOUNTANT rank=3 chosen; seed A-02-02/C-01-01 are groups (v1 fallbacks post to groups until T15 — transitional, wipe-and-reseed).
Ruling: UserRole rank for ACCOUNTANT must equal PROPERTY_MANAGER's so only TENANT_ADMIN/SUPER_ADMIN can create accountants (an accountant can post journals; a PM must not be able to mint one) — cost if wrong: one enum line. Carry into fix round if reviewer does not raise it.
Task 1+2: review (opus ac2cce7eb8e6f13be) — spec ❌ (fallout not contained), 4 Important, 7 Minor.
Ruling: F1 (web page silently creates orphan accounts) — containment only in plan 1: `CreateAccountRequest`/`UpdateAccountRequest` reject unknown properties (400 on legacy `parentCode` bodies); the page rewrite stays in Task 12 — cost if wrong: the accounts page errors until Task 12 lands, which is inside this branch.
Ruling: F2 (v1 fallbacks resolve group accounts, plan-mandated) — PARKED: v1 posting paths are dead on this branch and are deleted in Task 15 (wipe-and-reseed, spec D4); do not extend ChartOfAccountsFallbackCodesTest, delete it in Task 15 — cost if wrong: a tenant seeded from this branch before merge posts to groups; none exists.
Ruling: F3 — `active`/`displayOrder` become nullable wrappers, null = unchanged; `propertyId` is always applied (null clears) and the endpoint javadoc says so — cost if wrong: one more field semantics to revisit in Task 12.
F4 — fix as found (resolve property via tenant-filtered PropertyRepository, NotFound when absent) in controller and createLeaf.
Ruling (carried): ACCOUNTANT rank = PROPERTY_MANAGER's.
⚠️ /tree roots-only: Ruling — Task 12 keeps building the tree client-side from GET / (flat, has parentId); /tree + /children stay as extras — cost: none. ⚠️ ACCOUNTANT authority: covered by Task 9's JournalControllerIT.
Task 1+2: minor (deferred): M5 zero-line entry commits (per-line trigger cannot see an empty entry); M6 rollback block asymmetric (plan-mandated); M7 AccountRepository.findMaxNumericCode javadoc claims tenant filter applies to a native query; M8 nextLeafCode first-call race / null tenant / redundant save; M9 import depthInFile recomputed in comparator + duplicate code silently overwrites; M10 dead assertion AccountServiceTreeIT:113 + createLeaf never tested with propertyId; M11 no unique (journal_entry_id,line_no), no CHECK on journal_entries.status.
Task 1+2: fix round 1/5 dispatched — implementer hit Opus session rate limit mid-round (429), resumed, completed: commit 5351a94e; covering 39/39; full suite 1278/0.
Ruling: ACCOUNTANT rank strictly above PROPERTY_MANAGER (rank-equal + "PM cannot create accountant" not jointly satisfiable because canAssignRole is >=). Accountant out-ranks PM; UserController @PreAuthorize unchanged so accountants cannot assign roles today — cost if wrong: revisit before widening that annotation.
Ruling: @JsonIgnoreProperties(ignoreUnknown=false) is inert under Spring's mapper; implementer's @JsonAnySetter explicit refusal accepted as the containment for F1.
Task 1+2: fix round 1/5 (4 addressed, 0 open; commits 7098671b..5351a94e) — re-review sonnet ab3119beea95b9706 clean.
Task 1: complete (commits 34fcbb1c..5351a94e, review clean after 1 fix round; F2 parked)
Task 2: complete (commits 34fcbb1c..5351a94e, same batch)
Task 3: BASE 5351a94e — dispatching opus implementer (addendum-a Task 3 part: JournalLine.contraAccount)
Task 3: implemented (opus a85b04a7d2bc3f3ae) commit cd8d8a3d; ledger pkg 9/9, full 1287/0. Implementer replaced brief's TransactionTemplate create-race with INSERT … ON CONFLICT DO NOTHING in REQUIRES_NEW (brief's version double-issued numbers under race — caught by the concurrency test). JournalEntryRepository.search needed HQL casts for null params — no permanent test; CARRY to Task 8 dispatch: add a filtered search case.
Task 3: minor (deferred): AccountService.nextLeafCode creates the fiscal-settings row with saveAndFlush inside caller tx — same PK race (extends M8).
Task 3: review (opus a09580b492ba7172b) — spec ❌ (two constraint-named behaviours untested), 2 Important, 8 Minor. Both deviations from the brief confirmed correct.
Ruling: I2 (AccountService.nextLeafCode seeds fiscal row with saveAndFlush — pre-existing from T2, out of T3's file list) — fix in this round anyway: one line to insertDefaultIfAbsent; cheaper now than a T6 rediscovery — cost if wrong: none.
Task 3: minor (deferred): search() HQL casts untested until T8 (carried to T8 dispatch); String.format default locale in entry number (use Locale.ROOT); no-op repo.save in next(); bare NoSuchElementException in create(); getCreatesDefaultsWhenMissing pins the unreachable fallback; concurrency test asserts distinctness only; contraAccount has no write coverage until T5; OB counter lock held for a whole import batch (carry to plan 4 note).
Task 3: fix round 1/5 dispatched — commit 4509b0dd; full 1289/0
Task 3: fix round 1/5 (2 addressed, 0 open; commits cd8d8a3d..4509b0dd) — re-review sonnet a7406c8be3d1a3fb2 clean.
Task 3: complete (commits 5351a94e..4509b0dd, review clean after 1 fix round)
Task 4: BASE 4509b0dd — dispatching opus implementer
Task 4: implemented (opus a4546d47bb59e1352) commit 0ae36954; 6/6 unit, full 1295/0. Concerns: getMissingRoles returns live set; tenant filter on new repos unasserted until T6 IT.
Task 4: review (sonnet ad23eb39ce44a4672) — spec ✅, approved, 3 minor.
Task 4: minor (deferred): getMissingRoles exposes live EnumSet; empty-roles early return untested; tenant filter on the three mapping repos unasserted (first IT to seed them = T6).
Task 4: complete (commits 4509b0dd..0ae36954, review clean)
Task 5: BASE 0ae36954 — dispatching opus implementer (addendum-a Task 5 part: Pair/ofPairs/contra; reverse copies contra)
Task 5: implemented (opus abf6283431d22e9ae) commit b49c2fd0; PostingServiceIT 12/12; full 1307/0.
Ruling: changeset 81's four addUniqueConstraint entries were truncated to their first column by unquoted commas in YAML flow mappings (uq_journal_entries_number was unique on tenant_id alone). Implementer fixed 81 in place — accepted: append-only binds released changesets, 81 exists only on this unmerged branch; local dev DBs that ran 81 must be recreated (note for PR body) — cost if wrong: a stale local DB fails checksum, obvious and local.
Ruling: brief's `entries.count()` was cross-tenant because TenantAspect enables the Hibernate filter on a session discarded when a repository is called outside a transaction — replaced by a tenant-scoped JDBC count in the test. PARK for final review / a later hardening task: the aspect gap affects any repository call outside a transaction (platform-wide, pre-existing) — cost if wrong: none for this plan; flagged.
Task 5: minor (deferred): ofPairs does not assert pair amounts match; linkContraAccounts "exactly one counterpart" guard unreachable/untested; lines.saveAll dropped (cascade covers it).
Task 5: review (opus a2da2bd455025d2e0) — spec ✅, approved, 2 Important (I1 inactive-account guard untested on the ById path, plan-mandated; I2 ofPairs accepts mismatched pair amounts → wrong contra), 4 minor. Entering fix loop.
Ruling: reverse() copies importBatchId onto the reversal (M5) — keep: the reversal must be lock-exempt and belong to the batch; CARRY to plan 4: batch reverse skips entries with reversalOfId != null — cost if wrong: plan 4 double-reverses (rejected loudly by "reversal entry").
Task 5: minor (deferred): entry number allocated before validation (sequence row residue, benign); linkContraAccounts guard unreachable/untested; HashMap for pairs (nondeterministic guard message).
Task 5: fix round 1/5 dispatched — commit 969cf54c; PostingServiceIT 12/12 + PostingRequestTest 5/5
Task 5: fix round 1/5 (2 addressed, 0 open; commits b49c2fd0..969cf54c) — re-review sonnet a5c63f320fdb85713 clean.
Task 5: complete (commits 0ae36954..969cf54c, review clean after 1 fix round)
Task 6: BASE 969cf54c — dispatching opus implementer (pointer: T4 deferred minor — first IT seeding mapping tables should assert tenant isolation)
Task 6: implemented (opus a6a64ab29d9df0857) commit a03298ba; IT 6/6; full 1318/0. Concerns: importPropertyWithUnits + PortfolioImportPersistService bypass the generate hook; template seed 404s on an imported (non-seeded) CoA; PUT template is upsert not replace; findByNameAndParent_Id Optional throws on duplicate names; two brief deviations (lazy parent in test, null-input 400s).
Task 6: review (sonnet a1474a39425c4be3d) — spec ✅, approved, 3 minor.
Ruling: PropertyService.importPropertyWithUnits must also call generateMissing (fold into Task 7 dispatch); PortfolioImportPersistService bypass CARRIED to plan 4 Task 10 (it rewrites that path) — cost if wrong: imported properties need the manual "generate missing" button.
Task 6: minor (deferred): saveTemplate is upsert not replace (doc for web client); findByNameAndParent_Id Optional throws on duplicate names (no unique on (name,parent_id)); FORFEITED_INCOME is property-scoped but seeded as a tenant default (comment). Template seed 404s on an imported (non-seeded) CoA — CARRY to plan 4 cut-over (import must run template seed against PACT groups by name, not code).
Task 6: complete (commits 969cf54c..a03298ba, review clean)
Task 7: BASE a03298ba — dispatching sonnet implementer (+ importPropertyWithUnits hook)
Task 7: implemented (sonnet ae4ddf6315fc3a402) commit 32ae4ab5; IT 5/5; full 1323/0. Deviation: updateVendor keeps payableAccount unless explicitly supplied.
Task 7: review (sonnet aa2e96ca31a93d26d) — spec ✅, approved, 2 minor (vendor leaf nameAr unset on create; report citation nit — phrase was in the controller's dispatch, not the brief).
Task 7: complete (commits a03298ba..32ae4ab5, review clean)
Task 8: BASE 32ae4ab5 — dispatching opus implementer (addendum-a Task 8 part; carry: filtered JournalEntryRepository.search test; remove old FinancialTransactionController /ledger/vendor/{id} mapping)
Task 8: implemented (opus a819e9d4447a964a1) commit 2d442ffe; IT 7/7; full 1330/0. Concerns: mobile report_service.dart calls /finance/ledger/vendor (mobile frozen, plan 5 flag); truncated branch untested; getVendorLedger dead until T15.
Task 8: review (opus a35accc692a1e7a4c) — spec ✅, approved, 1 Important (general ledger unbounded: no date default + no total budget), 8 minor. Entering fix loop.
Ruling: general ledger `from` defaults to first day of the current month and `to` to today when absent (UI default anyway); add MAX_TOTAL_ROWS = 20000 across accounts setting `truncated` on the last account — cost if wrong: an accountant wanting "all time" must page by year.
Ruling (minor → fix now, cheap and plan-5-relevant): add `e.entry_number` as the final ordering key (deterministic running balance); add `l.tenant_id = :tenantId` to counterAccounts. — cost: none.
Task 8: minor (deferred): trialBalance silently drops rows whose account isn't found (log/throw); property-filtered TB may not balance (javadoc); large IN list defeats plan reuse; truncated branch untested; LineRow.getLineNo unused; mobile report_service.dart vendor-ledger caller broken (mobile frozen, plan 5).
Task 8: fix round 1/5 dispatched — commit d369ac0a; IT 8/8; full 1331/0
Task 8: fix round 1/5 (3 addressed, 0 open; commits 2d442ffe..d369ac0a) — re-review sonnet afdcb72d6a5dc727e clean. renterLedger also gets the total-row cap (accepted).
Task 8: complete (commits 32ae4ab5..d369ac0a, review clean after 1 fix round)
Task 9+10: BASE d369ac0a — batched (same shape: controller + HTTP IT); dispatching opus implementer
Task 9+10: implemented (opus a7ed7887dc4423ae6) commits 857dfa73 (ApiSecurityFilter admits ACCOUNTANT — pre-existing gap found by the ITs), 5a8b4ebc, 71d4d0c4; ITs 7+3, filter unit 24; full 1343/0. Concerns: bogus lease/renter dims in JV → generic 409 not 400.
Task 9+10: review (opus af980900c06b517b7) — spec ✅, approved, 6 minor. ApiSecurityFilter fix judged minimal and closes a context-less pass-through hole for ACCOUNTANT.
Task 9+10: minor (deferred): totalDebit N+1 on list (plan-mandated); page-size 200 cap untested; null line element → 500; ApiSecurityFilter test lacks "ACCOUNTANT without X-Tenant-Id" case (CARRY to Task 15 dispatch: add that unit test); PUT fiscal-settings is two transactions; dead null-check.
Task 9: complete (commits d369ac0a..71d4d0c4, review clean)
Task 10: complete (same batch)
Task 11: BASE 71d4d0c4 — dispatching sonnet implementer
Task 11: implemented (sonnet a84ef6484ab4b4f89) commit 0595a376; vitest 4/4 + full 332; tsc clean. Deviations: ROLE_RANK ACCOUNTANT=2 above PM=3 (matches backend ruling); Roles.ACCOUNTANT key casing per existing convention.
Task 11: review (sonnet aea188034797dcc81) — spec ✅, approved, 2 minor (comment the isGroup→group Jackson quirk in ledger.ts; client unexercised until UI tasks).
Task 11: complete (commits 71d4d0c4..0595a376, review clean)
Task 12: BASE 0595a376 — dispatching opus implementer
Task 12: implemented (opus ae5b9cde82c18a586) commit 17a0da35; vitest 335/335; tsc clean; lint baseline unchanged. Brief fixes: afterEach(cleanup); useDefault key vs inherited badge. Settings page unlinked until T14.
Task 12: review (sonnet adefcf425caeaec75) — spec ✅, approved, 1 Important (hardcoded English "Access Denied" heading on the template settings page), 2 minor (dead `disabled` prop on AccountPicker; picker re-fetches list the page already holds). Entering fix loop.
Task 12: fix round 1/5 dispatched — commit 5c3fd04d
Task 12: fix round 1/5 (1 addressed, 0 open; commits 17a0da35..5c3fd04d) — re-review haiku aee8a61de2db82e0d clean.
Task 12: complete (commits 0595a376..5c3fd04d, review clean after 1 fix round)
Task 13: BASE 5c3fd04d — dispatching opus implementer
User directive (2026-09-18): one PR per plan, stacked (plan N+1 branch cut from plan N branch); after plan 1 is validated, continue into plan 2 without pausing.
Task 13: implemented (opus a75bade4e9354e399) commit 0ffb0e39; vitest 339/339; tsc clean; lint baseline. Deviations: getAllByText for the repeated balance string; Link from @/i18n/routing (locale prefix); ISO-parts date formatting (UTC shift). Journals links 404 until T14.
Ruling: ACCOUNTANT missing from @PreAuthorize on GET /api/v1/units, /renters, /properties (list + by-id reads) — CARRY to Task 15 dispatch: add ACCOUNTANT to the read endpoints of those three controllers (reads only) with one HTTP assertion each — cost if wrong: an accountant sees property/unit/renter master data, which the ledger already exposes by name.
Task 13: review (sonnet a271fc1d9b46d422c) — spec ❌ (band wording), 2 Important (I1 "Tenant : x" vs PACT's "Tenant Name : x"; I2 ACCOUNTANT read access on units/renters/properties — already ruled into Task 15), 3 minor (journals link pending T14; CSV lacks truncation marker; redundant compound row key). Entering fix loop for I1.
Task 13: fix round 1/5 dispatched — commit c5a0b1dc
Task 13: fix round 1/5 (1 addressed, 0 open; commits 0ffb0e39..c5a0b1dc) — re-review haiku ad5c131fa777021f2 clean.
Task 13: complete (commits 5c3fd04d..c5a0b1dc, review clean after 1 fix round; I2 ruled into T15)
Task 14: BASE c5a0b1dc — dispatching opus implementer
Task 14: implemented (opus a552f122ecbac0872) commit a73b5359; vitest 342/342; tsc clean; build ok; lint +4 route-duplicate hits in untouched files. Deviations: new-journal page uses next/navigation+next/link with explicit locale (vitest can't resolve next-intl createNavigation); settings sidebar gates flattened so ACCOUNTANT sees setup links; ConfirmDialog gained children slot.
Task 14: review (sonnet a69f11d836b1681b8) Approved; 0 critical, 1 important (disclosure: success "toast" implemented as ?posted=1 banner on detail instead of a toast).
Ruling: banner-on-navigate accepted in place of the brief's toast — same confirmation, lands on detail, no toast primitive worth adding for one page — costs nothing if wrong (one-line swap later).
Deferred minors (final review): journals/[id] totals row says "Sub Total" (should be "Total"); fiscal page trigger button says "Lock period" vs dialog "Lock through".
Task 14: complete (commit a73b5359, review clean, no fix round)
Task 15: BASE a73b5359 — dispatching opus implementer
Ruling: T15 scope additions found by grep — StaffService/VendorService hold FinancialTransactionRepository (drop the fields; vendor ledger is LedgerQueryService since T8; a delete-guard based on FT rows is replaced by the journal-lines guard where an account exists, else dropped); web VendorPaymentDialog POSTs /finance/transactions → delete the dialog and its trigger on finance/vendors (plan 4 BPV replaces it, spec D8); help.ts route entries for the three removed pages dropped; e2e-prod prod-client helpers for account-mappings/transactions/trial-balance removed with their callers (04-reports, 13-finance, seed-demo-campus) — cost if wrong: vendors page has no "record payment" until plan 4; e2e-prod lost coverage is v1-only.
Ruling: AgingReportDTO/TicketReportDTO matched "ReportDTO" by substring only; they stay.
Task 15: implemented (opus af9de08fc3869eb67) commit 941e21c7; backend 1305/0; vitest 341; tsc clean; lint 133 (baseline 134). Deviations: brief's changeset-82 rollback YAML unparsable (": " in scalar) → quoted; brief's AccountDeletionService bullet mis-targeted (user self-deletion) → guard added to AccountService.deleteAccount instead; help articles/tour targets for removed pages left (stale, non-breaking).
Ruling (user directive 2026-09-18): "after each phase do a complete walkthrough of all the scenarios possible and do a playwright video of each testing and verify each flow" — Task 16 expands to a scenario walkthrough suite recording one Playwright video per flow against a live stack; same closing task for plans 2–5.
Task 15: review dispatched (opus) BASE a73b5359 HEAD 941e21c7
Task 15: review (opus a378cc7a7ff3f6e2f) Needs fixes — I1 deleteAccount guard untested; I2 finance-overview tour steps + 2 help articles still point at deleted pages; I3 mobile finance/settings/report services 404 (already ruled: mobile frozen, plan 5 MOBILE_FINANCE flag — not fixed here); minors: dead accountRepository fields (PaymentScheduleService/OnlinePaymentService + 8 test mocks), unused TenantContextHolder import, dead effectiveDateOrToday, guard misses tenant_default_account_mappings, stale v1 narrative in ChequeFailurePenaltyIT, webhook idempotency IT near-vacuous (CARRY to plan 2 brief: re-assert no double journal on redelivery).
Task 15: fix round 1 (resume af9de08fc3869eb67): I1, I2, minors 4-7. FIX_BASE 941e21c7
Task 15: fix round 1 done (dc4c3c94); backend 1309/0; vitest 341; tour steps re-pointed to sidebar-journals/general-ledger; 77 orphaned i18n keys (56 Finance, 21 Vendors) removed both locales; finance--chart-of-accounts article spot-fixed.
Ruling: the 77-key i18n removal is accepted — keys were referenced only by files 941e21c7 deleted, parity 0 diffs; plan 4 reintroduces vendor-payment copy with BPV wording — cost if wrong: re-adding strings.
Deferred (final review): finance--chart-of-accounts help article not proofread against v2 CoA.
Walkthrough stack (for T16): backend jar on :8081 (launch config rentaxis-backend-v2, DB rentaxis_v2 cloned from local rentaxis; 81+82 applied), web dev on :3001 (rentaxis-web). Login POST /api/auth/login admin@rentaxis.com/admin123 (SUPER_ADMIN, no tenant). Rebuild jar (./gradlew bootJar) + restart preview after any backend commit.
Task 15: re-review dispatched (sonnet a8e1c98f17255a913) on 941e21c7..dc4c3c94
Task 15: re-review clean (sonnet a8e1c98f17255a913). Task 15: complete (commits 941e21c7, dc4c3c94; review clean after 1 fix round)
Ruling: T16 Step 4 (push + PR) is withheld from the implementer — the PR is opened by the controller after the final whole-branch review, per the SDD protocol and Kunal's "PR per plan" directive.
Task 16: BASE dc4c3c94 — dispatching opus implementer (brief + walkthrough video directive)
Task 16: implemented (opus a14eaa97c244686bd) commits b1479fe5, 6ae4c478; backend 1309/0; vitest 341; e2e/finance 15 pass / 32 skip / 4 fail (pre-existing super-admin networkidle timeouts, reproduced without the change); walkthrough 15/15, 14 takes in web/walkthrough/takes/accounting-v2-plan1/; seed_demo_tenant.py exit 0 (added DEMO_WEB_BASE_URL; Azurite-less media 500s non-fatal).
Findings from walkthrough: P1 — POST /finance/accounts/seed 404s ("A-02-05-001") on a tenant that already has a non-PACT chart: seedDefaultAccounts short-circuits, then defaultIfMissing resolves never-created codes (hits every cut-over/demo tenant) → FINAL FIX WAVE (backend; rebuild jar). P3 — accounts POST accepts an EQUITY leaf under an ASSET group → final review triage. Scenario 12 records the tenant-ledger empty state (band lives inside account blocks; no postings until plan 2). Leaf-under-leaf is unreachable in the UI (groupOnly picker); server 400 proved via API, inline error shown via duplicate code 409.
Ruling: the 4 e2e super-admin failures are pre-existing and out of plan scope — left for the final review to confirm; cost if wrong: a flaky suite in CI we already have.
Task 16: review dispatched (sonnet) BASE dc4c3c94
Task 16: review (sonnet a876bd4f99abc8445) Needs fixes — 1 Critical: scenario 12 passes on a silently failed ledger fetch (page renders noRows alongside the error banner). Minor: TB 1,000 assertion echoes typed value (grand-total equality is the real check).
Task 16: fix round 1 (resume a14eaa97c244686bd) FIX_BASE f56c8c95. Final whole-branch review dispatched in parallel on f56c8c95 (read-only; fix wave covers both).
Task 16: fix round 1 done (8711237e); scenario 12 asserts GET 200 + no alert in main; mutation-checked (500 stub fails); 15/15 re-recorded. Re-review dispatched (haiku).
Task 16: re-review clean (haiku). Task 16: complete (commits 5e523dee, f56c8c95, 8711237e; review clean after 1 fix round). All 16 tasks complete; awaiting final whole-branch review (opus a30d1f3ac3638974d).
Final review (opus a30d1f3ac3638974d) on df10e39a..f56c8c95: Ready for PR after fix wave. C1 seed 404 on non-PACT chart; C2 concurrent reverse() double-mirror (no lock, no unique on reversal_of_id); I1 ACCOUNTANT sees Payments/Vendors/Bank/Staff links whose APIs 403; I2 verify-arabic.spec asserts deleted accountMappings link + 3 dead Navigation keys; I3 RTL physical classes in PropertyAccountsTab/AccountPicker/account-template; I4 EQUITY leaf under ASSET group; I5 ApiSecurityFilter admits tenant role with no tenant ctx (both branches); I6 postManual dims not tenant-checked; I7 null line → 500. Fix-now triage: M7 javadoc, Locale.ROOT in EntryNumberService, FORFEITED_INCOME comment, TB property-filter javadoc+page note, Sub Total→Total, Lock period→Lock through, help article proofread + helpArticles.ts:69, ApiSecurityFilterTest case, BankAccountService null check. Deferred: plan 2 (T4 EnumSet exposure), plan 4 (reverse importBatchId carry, saveTemplate upsert, duplicate-name generateMissing, totalDebit N+1, fiscal PUT two tx), plan 5 (zero-line trigger, TB missing-account log, mobile flag), auth/tenant hardening (TenantAspect gap). Dropped: F2, M6, M8, gap-in-numbers, HashMap msg, LineRow.getLineNo, page-size cap test, ACCOUNTANT reads (done), 4 super-admin e2e failures (branch touches neither spec nor page).
Ruling: C2's unique index goes INTO changeset 81 in place (81 is unreleased on this branch; PR already requires local DB recreation) instead of consuming 83, which plan 2 owns — cost if wrong: one more checksum reset for local devs.
Ruling: I6 fixed now (four existsById checks) — cheap and plan 2 builds on postManual's dims path.
Final fix wave: dispatching opus, FIX_BASE 8711237e
Final fix wave: done (opus a25030b18e17e8455) commits 58412a35 (backend), 6db3825a (web); backend 1324/0 (--rerun-tasks); vitest 346; lint 133. canAccessFinanceOps excludes PM (Vendors/Bank/Staff refuse PM; walkthrough pins PM at zero finance links) — Ruling: accepted; Payments page is deleted in plan 2 anyway. Navigation.transactions/reports were already absent. Left: finance--chart-of-accounts.md roles lacks ACCOUNTANT → include in the Playwright re-run resume.
Final fix wave: scoped re-review dispatched (opus) 8711237e..6db3825a; stack rebuild for e2e/walkthrough re-run.
Final fix wave: re-review clean (opus a9251eaa2aec99188); no regressions. Notes → PR body/follow-ups: C1 partial seed sticky (template block guarded by count()==0; plan 4 cut-over revisits), accounts page type-switch after parent pick → 400 (narrow UI wart, plan 4/5 polish), cross-tenant reverse untested (filter applies via JPQL; plan 5 golden tests), BankAccountService comment wrong (nullable=false). help article roles already include ACCOUNTANT (implementer's concern was moot). Commit trailers on 58412a35/6db3825a say Opus → amend to Fable after the re-run commit lands.
PLAN 1 COMPLETE: PR https://github.com/kunal-ak23/rentaxis/pull/263 (head a627f9dd). Workspace deleted.
