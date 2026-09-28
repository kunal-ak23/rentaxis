# Gate passes: web app has no Create/Approve/Scan/Walk-in/Policy UI — only a read-only report

- Found by: tutorial 25 prep (batch 6), reading `web/src/app` and comparing against the backend and the seed script.
- Stack: n/a (code read, no stack run) — repo at `chore/tutorials-web-refresh`, `web/src` as of this branch.
- Blocks: tutorial 25 in full. Catalog description (`web/src/lib/tutorials/catalog.ts:241`) promises "Issue and approve
  gate passes, admit walk-in visitors, set the access policy and review entry reports" and lists roles
  `[...PORTFOLIO, "RENTER", "SECURITY_GUARD"]`. Only the last item (entry reports) exists on the web.

## What's missing

A full-text search of `web/src/app` for "gatepass"/"gate pass" finds exactly one route:
`web/src/app/[locale]/dashboard/gatepass/page.tsx`, and it is a read-only scan-audit report (`GatePass.reportTitle`,
"Gate Pass Report" — date range, property filter, CSV export, a table of past scans). There is:

- No Tenant-facing "Create Pass" screen (issue a gate pass for an expected visitor).
- No manager "Gate Pass Approvals" screen (approve/deny a pending pass).
- No Security scan/admit/exit screen, no "Expected Visitors" list, no walk-in registration screen.
- No gate-policy settings screen (guards, vendors, access rules).

`/dashboard/gatepass` itself is gated to `canViewGatePassReport` = System Admin, Company Admin, Property Manager only
(`web/src/lib/rbac.ts`, `web/src/lib/nav/routeRegistry.ts:43`) — a Tenant or Security Guard cannot even open this one
page, despite being listed in the catalog's `roles` for tutorial 25.

## Why this is surprising

The backend fully implements the workflow the narration and catalog describe:
`backend/src/main/java/com/datagami/rentaxis/api/GatePassController.java` exposes create
(`POST /api/v1/gatepass`), approve (`POST /api/v1/gatepass/{id}/approval`), walk-in registration
(`POST /api/v1/gatepass/visitors/registration`), and policy configuration (`PUT /api/v1/gatepass/policies`), and
`scripts/seed_demo_tenant.py` (~lines 1613-1658) calls all of them to build a seeded pass, a walk-in visitor, a gate
policy, and scan history for the report page to show. The server side is done; only the web screens for creating,
approving, scanning, and administering passes were never built (or exist only in the Flutter mobile apps, which this
repo's `web/` tree does not cover).

## Expected / decision needed

One of:
1. The web app is missing real screens (Tenant "Create Pass", manager "Gate Pass Approvals", Security scan/walk-in,
   gate-policy settings) that should be built — a genuine product gap, not a tutorial problem.
2. This workflow is mobile-only by design, and tutorial 25's catalog entry / scope should be corrected to a short
   web tutorial that covers only the Gate Pass Report, with the interactive parts covered by a mobile tutorial
   instead (29-33 range).

Per the batch rules ("a real product bug stops that row"), I have not rewritten 25's scenario or narration to invent UI
that doesn't exist. `tutorials/capture/scenarios/25.mjs` already only touches the report page and its filters, which is
accurate as far as it goes; `tutorials/narration/25-gate-passes-and-walk-in-visitors.txt` narrates the full Create →
Approve → Scan → Walk-in → Policy flow, none of which is reachable on web, and needs a decision above before it can be
rewritten to match either a corrected (report-only) scope or new screens.

## Repro

1. Sign in as a Company Admin / Property Manager and open `/dashboard/gatepass` — only a report renders.
2. Sign in as a Tenant (RENTER) and try to reach `/dashboard/gatepass` — blocked by `canViewGatePassReport`.
3. Search `web/src/app` for any create/approve/scan/walk-in/policy gate-pass component — none exists.
