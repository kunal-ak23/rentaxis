# Scale track — 8,000 units a year

**Date:** 2026-09-25 · **Status:** approved direction (client chose a separate backend scale track) · **Scope:**
backend + web; runs alongside the web-only admin UI simplification (spec 2026-09-25-admin-ui-simplification).

## Intent
One client org will manage ~8,000 units and ~8,000 tenancy contracts a year (renewals every year), 4–12 cheques
per contract (30k–100k cheques/yr), ~100k recognition journals/yr and several years of history, with 3–20 staff.
Every daily screen must stay fast at that volume, and the daily work (renewal season, deposit day, move-outs,
front-desk lookup) must take a few clicks for hundreds of items, not one dialog per item.

**Success:** with a seeded dataset of 8,000 units × 3 years (≈24k contracts, ≈150k cheques, ≈300k journals),
every daily page loads in < 1.5 s (p95, local), no endpoint returns an unbounded list, no scheduled job loads a
whole table, and the renewal season for a month (~700 contracts) can be worked from one queue with bulk actions.

## Audit summary (2026-09-25)
Already fine: trial balance, journals list, cheque register (+ to-deposit, bounced), recognition run, payment
runs wizard, dashboard summary SQL, bookings, listings, meetings, notifications, reports' server-side PDF/CSV.

### P1 — breaks or unusable at target load
| # | Where | Problem | Fix | FE/BE |
|---|---|---|---|---|
| 1 | `NotificationScheduler.checkExpiringLeases` | `leaseRepository.findAll()` across all tenants/years, 3×/run, filtered in Java | query by `status IN (ACTIVE,NOTICE_GIVEN) AND end_date = :d` per tenant; index | BE |
| 2 | `LeaseService.mapToDTO` | 5–6 queries per lease row (ejari, documents, reduction exists, successor, lines + rent-free) | batch-load per page (IN queries / one projection) | BE |
| 3 | `GET /renters`, `/units`, `/maintenance-tickets`, `/vendors`, `/payment-runs`, `/issued-cheques`, `/cheques/post-dated`, `/cheques/aging`, `/payables-aging` | unbounded lists | add paged variants with server search + filters (property, status, dates); keep old endpoints for back-compat but web moves off them | BE+FE |
| 4 | Web `tickets` page | loads all tickets + all properties + all renters + **all 8,000 units** on every visit, filters client-side | use paged tickets with server filters; async unit picker | FE (after 3) |
| 5 | Web `leases` page / LeaseWizard | loads all units + all renters on mount for pickers | async search pickers | FE (after 6) |
| 6 | Pickers everywhere (`useNameLookup("units"/"renters")`, unit/renter/tenant selects) | whole tables loaded for a dropdown or to label a few rows | server search endpoints `GET /units/search?q=&propertyId=&limit=`, `GET /renters/search?q=&limit=`, `GET /units/names?ids=`, `GET /renters/names?ids=` + a shared `AsyncPicker` | BE+FE |
| 7 | Indexes | none on `units` / `properties`; none on `leases.end_date` | `units(tenant_id, property_id, status)`, `properties(tenant_id)`, `leases(tenant_id, status, end_date)`, trigram/GIN (pg_trgm) on renter name/phone/email + unit no for search | BE |
| 8 | Web `renters` page | full renters table, client search/sort/paging | paged + server search | FE (after 3) |

### P2 — slow or painful
| # | Where | Problem | Fix | FE/BE |
|---|---|---|---|---|
| 9 | `DashboardService` overdue sum; `NotificationScheduler.findAllDue` | in-memory aggregation over all due cheques | SQL SUM / paged scan | BE |
| 10 | Penalty queue | hard cap 200, no pagination → silently hides backlog | paged | FE (+BE if needed) |
| 11 | Recognition pending | unbounded backlog fetch if the close falls behind | paged/grouped server-side | BE+FE |
| 12 | General ledger | unpaginated; wide ranges load years × all accounts; no virtualization | account picker first + 12-month default (UI PR 2 amendment) + server row cap already exists → surface truncation; virtualize table | FE |
| 13 | Exports (P&L, statement, balance sheet, GL CSV) | whole file built in memory, no caps | stream CSV; cap/queue very large PDFs | BE |
| 14 | Renter detail | one cheque fetch per lease | add `unclearedAmount`/`liveAmount` (excl. REPLACED/CANCELLED) to `LeaseChequeStatsDTO`, include draft/awaiting contracts, then batch the renter page (UI PR 2 attempt reverted: it lost the Outstanding/Cheques total tiles) | BE+FE |
| 15 | Gate-pass report | full date range to browser | paged | BE+FE |

### Operational ease (new capabilities)
| # | Capability | Detail | FE/BE |
|---|---|---|---|
| 16 | **Renewal season queue** | Contracts filtered by expiry month/property/floor ("Renewals due in October: 312"); row actions (renew, extend, mark not renewing, contact); **bulk renew** with one rule (no change / +x% / +amount, whole AED) producing drafts for review, then **bulk post** | BE (`leases/paged` endDate range + `POST /leases/renew-batch`) + FE |
| 17 | **Bulk terminate** (PACT "Bulk Terminate") | select contracts ending on a date → preview each → confirm | BE + FE |
| 18 | **Bounce in one flow** | bounce → replace cheques → bounce fee/penalty in one dialog | FE (existing endpoints) |
| 19 | **Keyboard-first cheque grid** | Enter/arrows move cells, paste rows from Excel, fill-down | FE |
| 20 | **Unit status board** (PACT "Floor Wise Expiry", "Daily Vacant Flat") | per property, grouped by floor: Occupied / Reserved / Expiring ≤60d / Vacant / Maintenance, click → unit/contract side panel | FE (existing per-property endpoints) |
| 21 | **Global quick search** | already exists (⌘K) over contracts + cheques; add tenants and units via the new search endpoints | BE (6) + FE |
| 22 | **Saved/sticky filters** | URL-persisted filters on tenants, properties, units, tickets (cheques & contracts already do) | FE |
| 23 | **Parallel bulk post drafts** | today sequential; run with limited concurrency + progress | FE |

## Baseline measured (2026-09-25, local, 8,000 units × 3 years: 60 properties, 12,807 renters, 22,876 contracts,
158,637 cheques, 577,079 journals; details scratchpad scale-baseline.md)
| Endpoint | p95 | size | statements |
|---|---:|---:|---:|
| GET /leases (unbounded) | 196.7 s | 61.2 MiB | 160,645 |
| GET /tickets | 25.8 s | 5.0 MiB | 19,889 |
| GET /cheques/aging | 12.2 s | 1.0 MiB | 9,552 |
| **GET /dashboard/summary** | **11.6 s** | 3 KiB | **9,499** |
| GET /units | 9.9 s | 4.3 MiB | 4 |
| GET /properties | 4.6 s | 21 KiB | 121 |
| GET /renters | 2.4 s | 2.8 MiB | 1 |
| paged leases / cheques / journals | 0.13 / 0.34 / 0.03 s | — | — |

**New P1 items from the measurement (Scale PR A, first):**
- **P1-9 Dashboard summary: 9,499 statements per load (N+1)** — every login hits it; make it a handful of
  aggregate queries (target < 300 ms).
- **P1-10 Cheque aging: 9,552 statements** — aggregate in SQL.
- **P1-11 Open-session-in-view makes batch jobs crawl** — month-end recognition of ~1,900 entries took 315 s
  with OSIV on vs ~8,200 entries in 35 s with it off; cheque clearing ~8× faster. Turn `spring.jpa.open-in-view`
  off, fix every LazyInitializationException it exposes (DTO mapping inside @Transactional services), and give
  bulk jobs (recognition run, deposit/clear batch, VAT tax points, catch-up) chunked transactions.
- **P1-12 Concurrent purchase-invoice posting → 500** (77/2,000 at 8 threads; Hibernate exception inside
  `VoucherService.lockForWrite`) — make the lock path concurrency-safe (409 + retry-safe, never 500).
- Properties list 121 statements (stats per property) → one aggregate query.

## Delivery
- **Scale PR A (BE, P1 core):** #1, #2, #7, #9 + paged/search endpoints for renters, units, tickets, vendors,
  payment runs, issued cheques, post-dated, aging, payables aging (#3, #6 backend). Old endpoints kept.
- **Scale PR B (FE):** move pages to the paged endpoints and async pickers (#4, #5, #6 FE, #8, #10, #14, #15 FE, #22, #23).
- **Scale PR C (ops):** renewal season queue + bulk renew (#16), bulk terminate (#17).
- Web-only items #12, #18, #19, #20 go into admin UI PR 2/PR 3 (frontend-only, per that spec).
- **Proof:** a seed script creating 8,000 units × 3 years locally; timings before/after for every daily page and
  endpoint, recorded in the PR bodies. Golden gate stays green; tenant isolation (Hibernate filter only inside
  @Transactional; native SQL binds tenant_id) on every new query; Liquibase append-only; EN/AR.

## Out of scope
Multi-org fan-out optimisation of scheduled jobs beyond per-tenant bounded queries; read replicas; caching layers.
