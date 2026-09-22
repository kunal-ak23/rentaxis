# Accounting v2 — handoff after plan 2, Task 15 (2026-09-19)

Read this, then `docs/superpowers/handoff/2026-09-19-plan2-sdd-ledger.md` (the execution ledger: every task, review verdict and **Ruling:** line). The ledger is the recovery map; trust it and `git log` over anything else.

## What this project is
RentAxis is replacing the client's PACT RevenU accounting. Spec: `docs/superpowers/specs/2026-09-17-accounting-v2-design.md`. Five plans under `docs/superpowers/plans/2026-09-17-accounting-v2-plan{1..5}-*.md`, executed with the superpowers **subagent-driven-development** skill (fresh implementer per task → task review → fix loop → final whole-branch review → PR).

## Standing directives from Kunal
- Model tiering: Fable only for planning/critical review; implementers on Opus (backend, ledger-critical) or Sonnet (web/moderate); Haiku for tiny re-reviews.
- Plain branches, never git worktrees. One PR per plan, **stacked** (plan N's PR base = plan N−1's branch).
- After each plan: a recorded Playwright walkthrough, one video per scenario, assertions in the same run, videos sent to Kunal (`web/walkthrough/accounting-v2-plan1.{config,spec}.ts` is the pattern; takes are gitignored under `web/walkthrough/takes/`).
- Per-day rent recognition is a hard client requirement (plan 3): day rate = annual rent ÷ term days; month = day rate × actual days; last period absorbs rounding.
- Use Kunal's GitHub login: `gh auth switch --user kunal-ak23` (other logins on the machine cannot see the private repo).
- Commit trailer: `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`; conventional commits; never `git add -A` at the repo root (stray untracked files); stage explicit paths.

## Branches
| Branch | State |
|---|---|
| `feat/accounting-v2-ledger-core` | Plan 1 complete. PR #263 → `main`, open. |
| `feat/accounting-v2-lease-posting` | Plan 2, stacked on plan 1. Last green commit `21fbebb9` + this handoff commit. Backend 1595 tests / 0 failures; web vitest 428 passing, tsc clean, build ok. **No PR yet** — it is opened after Task 17 + the final whole-branch review, base = `feat/accounting-v2-ledger-core`. |
| `wip/accounting-v2-plan2-interrupted` | One **unverified** WIP commit (`9f6f71f0`): two agents were cut off by a usage limit mid-edit. Never compiled or tested. Cherry-pick what is useful or redo. |

## Plan 2 status (17 tasks)
Complete and reviewed clean: **1** changeset 83 · **2** charge types (credit-role allow-list) · **3** Cheque entity/register queries · **4** lease lines + draft leases · **5** cheque grid generation (VAT-inclusive via `LeaseVat`) · **6** `LeasePostingService` post/dryRun/amend · **7** `ChequeService` lifecycle (one journal per transition; gateway methods) · **8** renew chain + extend (chain-aware deposit carry-forward) · **9** approval-gated penalties · **10** online payments on the register (`CAPTURED_UNAPPLIED`, amount validation) · **11** cheque API + reader rewires (2 fix rounds) · **12** changeset 84, v1 schedules/charges/penalties deleted, settlement on ledger/register · **13** web API client/RBAC/i18n.

In flight:
- **Task 14 (web lease editor)** — implemented (`d629d31a`, `5ed1ad85`, `44e12933`, `2767065e`), reviewed **Needs fixes, 0 Critical**. Fix round 1 was interrupted; partial edits are on the WIP branch. Items to close:
  1. `components/leases/ChequeGrid.actionsFor` must follow the server state machine and share ONE definition with the register (`web/src/components/cheques/registerActions.ts`): REGISTERED+PDC → Deposit, Details, Cancel(`canCancelCheques`); REGISTERED+CASH/TRANSFER → Receive, Details, Cancel; DEPOSITED → Clear, Bounce; CLEARED → Receipt, Bounce only for PDC; BOUNCED → Replace; others none. Fix the test that pinned the wrong table.
  2. Terminate was re-gated to SA/TA; the settlement API admits SA/TA/PROPERTY_MANAGER → add `canTerminateLeases` and restore. Diff every other re-gated button against `git show 637d2bc7:"web/src/app/[locale]/dashboard/leases/[id]/page.tsx"`.
  3. Leases list: send `status`/`propertyId` to `GET /api/v1/leases/paged` (server params exist since `b70f770b`); drop the client-side filter.
  4. Amend/Renew/Extend dialogs: confirm disabled while any line is invalid (discount > amount, no charge type, non-positive amount) via one shared `linesAreValid` helper.
  5. Wire or remove `ChequeGrid`'s unused `chequeTotal` prop; delete orphaned `web/src/components/leases/ChequeActionDialog.tsx`; show the access-denied panel on a 403.
  Then a scoped re-review.
- **Task 15 (web cheque register)** — implemented (`21fbebb9`); its **review was interrupted and has not produced a verdict** — re-dispatch it. The review prompt's checklist: one shared action table (status × mode × role), dialog bodies vs `leasing.ts`, replace residual math, collection selection survives a refusal, post-dated grouping, no `toISOString()` date shifts, RTL logical classes, sidebar gating by `canManageCheques`, deletions left no dangling imports, bulk-attach body vs `BulkAttachChequesRequest`, `data-testid`s.
- **Backend add-on (ruled)**: `GET /api/v1/online-payments/unapplied` (+ `/count` → `{count,totalAmount}`), roles SA/TA/ACCOUNTANT, lists `CAPTURED_UNAPPLIED` payments with reason/amount/renter/cheque/gateway payment id; HTTP IT (appears; normal CAPTURED does not; PM/RENTER 403; second tenant sees nothing). Interrupted; partial code on the WIP branch. Task 16 then adds an "Online payments to refund" tile.

Not started:
- **Task 16** — web penalties queue (`/api/v1/penalties`), lease penalties tab polish, renter portal (due rows, approved penalties, Razorpay `PayOnlineButton`, receipts at `/api/v1/cheques/{id}/receipt`), fines settings (three new fields: `bouncesBeforePenalty`, `autoProposeChequeReturn`, `autoProposeLatePayment`), sidebar Penalties link, delete `RecordPenaltyPaymentDialog`. The renter portal pages still call deleted v1 endpoints (`my-payments` old shape, `/v1/payments/{id}/receipt`, `create-order` with `paymentScheduleId`).
- **Task 17** — full verification; rewrite `web/e2e/leases/*` + new `web/e2e/finance/cheques.spec.ts`; e2e helpers still call the deleted `PUT /leases/{id}/activate` (`web/e2e/helpers/api-client.ts`, `web/e2e-prod/helpers/prod-client.ts`); fix `scripts/seed_demo_tenant.py`; five stale walkthrough specs under `web/walkthrough/` reference v1 payments; **plan 2 walkthrough with one video per scenario** (draft with lines → cheque grid → dry run → post → deposit batch → clear → bounce → replace → penalty propose/approve/collect → cash receipt → renew with deposit carry → extend → amend → renter pays online → accountant vs PM permissions → Arabic RTL); then the final whole-branch review on the most capable model, one fix wave, scoped re-review, then the stacked PR.

## Local walkthrough stack
`.claude/launch.json` (gitignored; recreate if missing): `rentaxis-backend-v2` runs the built jar on :8081 against DB `rentaxis_v2`; `rentaxis-web` runs Next dev on :3001 with `BACKEND_URL=http://localhost:8081`. Create the DB with `CREATE DATABASE rentaxis_v2 TEMPLATE rentaxis` on local Postgres (drop and recreate whenever changesets 81–84 change — they were edited in place on these unreleased branches). Rebuild the jar (`./gradlew bootJar`) after backend commits. Login `POST /api/auth/login` `admin@rentaxis.com` / `admin123` (local dev super admin).

## Deferred items the final review must triage (also in the ledger)
`seq_no` has no DB uniqueness (service lock only) · two active gateway configs resolve positionally (needs `online_payments.tenant_gateway_config_id`) · INR currency fallback in `OnlinePaymentService.currencyOf` · dead `PENALTY_CLEARED/WAIVED` email wiring · paged leases still finish in memory for PMs/free-text · `rent_collection_settings.grace_period_days` has no consumer · imported ACTIVE leases keep an unposted DRAFT grid (plan 4 owns "imports are posted") · settlement arrears are register-based until plan 3 · `unit.current_lease_id` from spec §6.4 does not exist · mobile is frozen and its finance/lease-activate/extend calls now 404 (plan 5 `MOBILE_FINANCE` flag) · platform: `TenantAspect` enables the tenant filter only inside a transaction (every new service path is `@Transactional` for that reason).

## After plan 2
Plan 3 (`…plan3-recognition-termination-settlement.md`, changesets 85–86) on `feat/accounting-v2-recognition` stacked on plan 2; then plans 4 and 5. Plan docs were already patched for two YAML hazards (unquoted multi-column `columnNames` in flow mappings; `SELECT 1 -- …` rollback scalars).
