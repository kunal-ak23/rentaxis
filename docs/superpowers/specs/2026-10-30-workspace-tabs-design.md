# Workspace tabs (PACT-style multi-tab) — design

**Date:** 2026-10-30 · **Status:** draft for Kunal's review, no code until approved · **Scope:** web dashboard (`web/`) only; the Flutter apps are untouched

## Intent

The client's staff come from PACT, a Windows desktop app where every screen opens in its own window inside
the app. An accountant keeps a tenancy contract, a journal voucher, the General Ledger and a tenant's ledger
open at once and flips between them. Each one is exactly as they left it: the half-typed voucher, the
filtered and scrolled ledger, the contract on its Cheques section. In Miftah today every click replaces the
one screen there is. Going back means finding the screen in the menu again, re-applying filters and
scrolling. Anything half-typed is gone.

**Success means:** a user opens the four screens above, switches between them with one click or one key
press, and finds each one as they left it, including unsaved entries in the contract wizard, journal voucher
and voucher forms. This also holds after a reload or a sign-in timeout. Nothing that works today breaks: deep links, permissions, Arabic, phones, the test suites
and the tutorial recorder.

## Goals, non-goals, anti-goals

- **Goals:**
  - A visible list of open screens inside Miftah, with fast switching by mouse and keyboard.
  - Each screen comes back with its filters, section and scroll position.
  - Unsaved entries in the screens the client named (contract wizard, journal voucher, vouchers) are never
    lost silently.
  - The workspace survives a reload and a sign-in timeout.
  - Works in Arabic (RTL) as well as English.
- **Non-goals:**
  - Floating, overlapping windows (MDI).
  - Several organisations open side by side.
  - Syncing tabs across devices.
  - Changing the mobile apps.
  - Changing any backend API, apart from one feature flag for the pilot.
- **Anti-goals.** Any of these makes the feature a failure, even if tabs appear:
  - A screen shows another tab's data, or another organisation's.
  - A user loses typed work without being asked.
  - The app slows down as tabs pile up.
  - Existing URLs or bookmarks stop working.
  - The Playwright suites and the tutorial recorder need rewriting.

## Constraints

- Next.js 16.3 App Router, React 19.2, next-intl (`/en`, `/ar`), NextAuth JWT sessions (12 h max, re-checked
  every 5 min).
- All browser API calls go through `/api/proxy`. One `fetch` guard (`proxyFetchGuard`) handles 401 and
  organisation mismatch for the whole page.
- The active organisation is one cookie shared by every browser tab. A switch in one browser tab blocks the
  others with a reload notice (`SessionGuards`, break round 1 F3).
- Table-first list layouts; RTL via logical CSS (`start`/`end`, `ps`/`pe`).
- The repo is public: no client names or PACT data in code, docs or fixtures.
- Plain branches, no worktrees.

## Current state (what the code does today)

- **Shell:** `app/[locale]/dashboard/layout.tsx` → `AuthenticatedLayout` (client component). It renders
  `SessionGuards` › `TourProvider` › `NavShellProvider` › rail + section panel (`MvpSidebar`, `SectionPanel`)
  › `TopHeader` › `<main>{children}</main>`. The nav model (`lib/nav/navModel.ts`) builds the rail per role.
  `activeNav()` maps a pathname to its section and item (the header breadcrumb uses it).
  `lib/nav/routeRegistry.ts` lists every dashboard route with its role rule. `lib/nav/routeMap.ts` 308s moved
  URLs (`ROUTE_MOVES`, `legacyRedirect`).
- **Pages:** 84 dashboard pages, 72 of them client components. Each fetches its own data in effects. The
  fetch guard counts ~150 raw `fetch("/api/proxy/…")` calls across ~75 files. There is no shared client cache
  (no SWR or React Query).
- **View state:** some of it already lives in the URL.
  - Contracts list: `?view=`, written with `history.replaceState`.
  - Contract page: `?tab=&section=`.
  - Tenant ledger: `?view=`.
  - General Ledger: reads its filters from the URL once, when it mounts, and writes them back on Apply. It
    does not re-read them later (see §4).
  - Contracts list: view mode (table, cards, board) and page size are component state.
- **Forms:**
  - The contract wizard is a modal on the contracts list. Its open state and its first three steps live in
    component state. It saves a server-side DRAFT at the end of the Charges step and can resume that draft.
  - Journal entry (`finance/journals/new`) is pure component state.
  - Vouchers have a server DRAFT status.
  - Only two screens warn before leaving: opening balances and bulk cheque upload
    (`useUnsavedChangesWarning`).
- **Links:** most navigation uses `Link`. About 26 places call `router.push` (post-save redirects, card
  clicks). The contract card view uses a clickable `div`, so Ctrl-click does not open it in a new browser tab.
  Every page's document title is just "Miftah" / "مفتاح".
- **Keep-alive in Next 16:** with `cacheComponents: true`, Next keeps recently visited routes mounted (hidden
  with React `<Activity>`). The limit is hard-coded at 3 (`MAX_BF_CACHE_ENTRIES` in
  `next/dist/client/components/bfcache-state-manager.js`), ordered by recent visits rather than by tabs.
  Turning the flag on also switches the app to Partial Prerendering. That is a platform migration, and
  `cacheComponents` is off today.
- **Tests:** about 240 Playwright specs (`e2e/`, `e2e-prod/`, `walkthrough/`) make about 3,000
  `getByTestId`/`locator` calls, and the tutorial recorder drives the same pages. All of them assume one
  page in the DOM.

## Options compared

| | (a) Keep-alive tabs | (b) Route tabs, reload on switch | (c) Real browser tabs | (d) Split / MDI workspace |
|---|---|---|---|---|
| **What it is** | Tab strip; every tab's page stays mounted, hidden while inactive | Tab strip; a tab remembers its URL; switching navigates and the page reloads its data | Use the browser's own tabs and windows; Miftah only makes that work well | Two or more screens visible at once (panes or floating windows) |
| **Memory / speed with many tabs** | Grows with every tab: ten live tables and forms, plus their DOM. Needs its own cap, beyond which tabs reload anyway (= b). Switching is instant. | Flat: one page is mounted. Switching costs one client navigation plus that page's API calls (same as a menu click today). | Each browser tab loads the whole app again. The browser manages memory and may discard idle tabs. | Every visible pane is live, so like (a) for the visible ones |
| **Unsaved forms** | Kept while mounted. Lost when the tab is evicted or the page reloads. Effects re-run on re-show, so every page must be audited for re-init bugs. | Lost unless the screen saves a draft. Needs a per-tab draft store for the forms that matter. | Kept while the browser tab lives. Lost on browser close; only 2 screens warn today. | As (a) |
| **Auth / session expiry** | The 401 redirect reloads the document: every mounted tab and its state is gone unless drafts are stored anyway | The tab list and drafts are in storage, so all tabs come back after sign-in | Each browser tab hits the expiry separately and redirects on its own | As (a) |
| **Arabic RTL** | Strip mirrors with logical CSS | Same | The browser's chrome follows the OS language, not the app's | Pane order and dividers must mirror; floating windows mirror poorly |
| **PACT-like keys** | In-app shortcuts possible (below) | Same | Browser's own keys only (Ctrl+Tab, Ctrl+1…9); Miftah screens are mixed with mail and chat | In-app focus keys between panes |
| **Persistence across reloads, per user** | Tab list can be stored; the live state can't | Tab list and drafts stored per user and organisation | Browser "continue where you left off" (reloads every tab) | Layout can be stored |
| **Deep links / URL** | Hidden tabs read `useSearchParams`/`usePathname`/`window.location` of the *active* URL. Needs per-tab overrides of Next's internal navigation contexts and fixes in 10 pages that read `window.location`. | URL = the active tab's screen, exactly as today. Shareable, bookmarkable. | URL = that browser tab's screen (ideal) | Two screens, one URL: needs a layout encoding or parallel routes |
| **Mobile** | Unaffected if hidden below 768 px | Unaffected if hidden below 768 px | Unaffected | Unusable below ~1280 px |
| **Build cost / risk** | High. Either `cacheComponents` (platform migration, still only 3 routes, not tab-aware) or a second router that renders pages outside Next's, relying on Next internals that change between releases. | Medium | Low (titles, real links, leave warnings) | High; same per-pane rendering problem as (a), and dense ledgers do not fit half a laptop screen |
| **Test impact** | Hidden copies keep their test ids in the DOM: `getByTestId` matches duplicates and the ~3,000 locators and the tutorial recorder break whenever a tab is hidden | None while one page is mounted | None | As (a) |

## Recommendation

**Build (b) and give it a "tab memory" so it behaves like (a) where it matters. Add (c)'s hygiene first.**
Do not build (a) or (d) now.

- **(b)** keeps one page mounted, so memory stays flat, URLs keep their meaning, and every existing test and
  tutorial still works.
- **Tab memory** makes each screen come back as the user left it. It has three parts: view state in the URL,
  scroll restored by the tab host, and per-tab drafts for the forms the client named. This is what the client
  actually experiences in PACT, without keeping every page alive.
- **(c)'s hygiene** is cheap and useful on its own: real page titles, real links that Ctrl-click opens in a
  new browser tab, and leave warnings. It also gives users with two monitors a second window today.
- **(a)** saves the switch time: under half a second on most screens, 1–3 s on big ledgers and the Trial
  Balance (measured, §11). It costs a second router, re-init audits across 84 pages and broken test suites.
  Making those few screens faster is cheaper. If the pilot still finds switching too slow, Phase 3 revisits
  a narrow keep-alive.
- **(d)** is the wrong shape for dense ledgers on laptop screens. Its useful core, looking at a voucher or a
  ledger without leaving the current screen, comes back as a read-only side "peek" in Phase 3 if the pilot
  asks for it.

Mockups: the "Miftah workspace tabs" design canvas (link in the review note). It has a clickable version of
the recommended strip (EN/AR, unsaved dot, close confirmation, menu, open-a-screen list) and static
comparisons of (c) and (d).

## Design of the recommended option

### 1. Words

- **Workspace:** the set of tabs one user has open in one organisation, in one browser tab.
- **Tab:** `{ id, href, title, kind, openedAt }`.
  - `href` is locale-less: path plus query, e.g. `/dashboard/finance/general-ledger?from=…&propertyId=…`.
  - `kind` is `screen` (a list, report or form reached from the menu) or `record` (one contract, voucher,
    tenant, vendor, ticket, property).
- **Active tab:** the one whose screen is mounted. The address bar always shows its `href`.

### 2. Where it lives

- A 40 px strip between `TopHeader` and `<main>` in `AuthenticatedLayout`, inside `SessionGuards`, so the org
  notice and the session handling cover it too.
- Each tab shows an icon, a title (truncated, full title on hover) and an unsaved dot, with a close button
  (×) at its end side. At the strip's end: "+" (open a screen) and "All tabs (n)", a menu that lists every tab
  and has "Reopen closed tab" and "Close other tabs (keeps unsaved ones)".
- Tabs shrink to a minimum of ~112 px. Beyond that they are reachable from the menu, and the active tab is
  always visible.
- The strip is a WAI-ARIA `tablist`. `<main>` is its `tabpanel`.

### 3. Opening rules (my call; client question Q1)

**Identity (my call).** Each route in `routeRegistry` gains an `identity` list: the query parameters that say
*which* record or view it shows. A tab's identity is its path plus those parameters.

- Vouchers: `id`.
- Tenant ledger: `renterId`.
- Record routes with the id in the path (`/leases/[id]`, `/journals/[id]`): nothing extra.
- Screens: none.

Everything else in the query is view state (filters, page, section).

| Action | Result |
|---|---|
| Plain menu item: rail panel or Ctrl+K screen result, with no query | Focus the most recently used tab on that path. The tab keeps its own query: the user's filters win. Otherwise open a new tab at the end. |
| Link that carries a query: pinned view (`/leases?view=expiring`), report drill-down (`/general-ledger?accountIds=…&effectiveProperty=true`) | Focus a tab only if its `href` is exactly the same. Otherwise open a new tab right after the current one. A drill-down never overwrites a ledger the user has set up. |
| Record link inside a screen: contract no., voucher no., tenant, vendor, "Ledger" links | Focus the tab with the same identity. Otherwise open a new tab right after the current one. |
| Navigation within a screen: filters, paging, sections, "back to list", the redirect after save (e.g. new JV → posted JV) | Stays in the same tab; the tab's `href` and title follow. |
| Ctrl/Cmd-click, middle-click, browser "Open link in new tab" | Left to the browser: a real browser tab (a separate workspace). |

- Links opt in through a `WorkspaceLink` (wraps the next-intl `Link`; `opens="screen" | "record"`) and
  `useWorkspace().open(href, kind)` for code that navigates by itself.
- A link that has not opted in navigates in place, exactly as today. This is the safe default while screens
  adopt the new links.

### 4. URL ↔ tab rules

The host watches pathname and query in the layout. On a change it did not start itself (an in-page link,
`router.push`, Back/Forward, a typed URL), it applies the first rule that fits:

1. An open tab's `href` equals the new URL → activate that tab.
2. A recently closed tab's `href` equals it → reopen that tab. This is Back after closing a tab.
3. Otherwise → the active tab takes the new URL (in-tab navigation).

Rule 3 applies only to client-side navigation after the page has loaded. **A fresh document load** (reload,
sign-in redirect, organisation switch, a pasted link) works differently:

- **Landing on an open tab's `href`** → activate that tab.
- **Landing on the bare home page (`/dashboard`) while the workspace has tabs** → replace the URL with the
  stored active tab's `href`. Today the organisation switch hard-navigates to home, and a sign-in without a
  `callbackUrl` lands on home. Neither may overwrite a tab.
- **Landing on any other URL** → open it as a new tab next to the stored active tab.

Phase 1 also gives the layout's unauthenticated redirect (`AuthenticatedLayout`, `router.push("/auth/login")`)
a `callbackUrl`, as the fetch guard's redirect already has.

**Every tab switch must remount the page (found by running it).** Next only remounts a page when the route
changes. Pages read their filters once, when they mount, so a switch between two tabs on the same route with
different queries keeps the old data. In the timing run, a General Ledger URL for one account showed another
account's rows. The host therefore renders `{children}` under `key={activeTabId}`. In-tab query changes keep
the same key, so they behave as today.

Switching tabs and opening tabs are history *pushes*. Back and Forward therefore walk through "where I was",
across tabs and within a tab, and rule 1 makes that land on the right tab. URL meaning is unchanged: a link
pasted into a fresh browser opens that one screen as a tab (rule 3, or rule 1 if it is already open). The tab
list itself is never in the URL, so shared links stay clean.

### 5. Tab memory

Each screen comes back as it was left, through three mechanisms, in this order of preference:

1. **View state in the URL.**
   - What goes there: filters, sort, page, page size, view mode, sub-tab and section.
   - Pattern: the contracts list's `replaceState` plus `useSyncExternalStore`, or `router.replace`.
   - A screen that does this needs nothing else to come back right.
2. **Scroll, restored by the host.**
   - On switch-out the host stores `<main>`'s `scrollTop` for the tab.
   - On switch-in it restores it once the content is tall enough. It retries for up to ~1.5 s while data
     loads, then gives up quietly.
3. **Drafts for unsaved input:** `useTabDraft(key, value, restore, { baseVersion })`.
   - **Storage:** a debounced (≈300 ms) write to `sessionStorage` under
     `ws:{userId}:{orgId}:{tabId}:{identity}:{key}`, where `identity` is the tab identity from §3.
   - **Coverage (my call):** drafts cover *every* unsaved edit, not just edits before the first server save.
     Two examples: the wizard's cheque grid before "Save cheques", and a voucher edited after "Save draft".
     A tab switch unmounts the page, so anything less loses work silently.
   - **Edits over a saved record:** the draft records the server `version` it was based on (`baseVersion`).
     On restore, if the server's version has moved on, the screen asks: "This record was changed since your
     edits: keep my edits / use the saved version". The wizard already has this kind of version handling
     (409 `lease.changed`).
   - **Restore:** on mount, if the tab has a draft, the screen restores it and shows a one-line "Restored
     unsaved changes · Discard" note.
   - **Clearing:** a successful save or post clears the draft. So does closing the tab after confirming.
   - **Navigating away in place:** moving the tab to another screen does not ask, and does not discard the
     draft. The draft stays under its old identity, so Back brings it back with the "Restored" note.
   - **Dirty dot:** the tab shows the unsaved dot while a draft exists for its current identity.
   - **Closing a tab:** asks if the tab holds a draft under *any* identity, and names the screens it would
     discard.
   - **Tab id:** `useTabId()` gives screens their tab's id.
   - **Why `sessionStorage`** (my call; client Q4): it survives tab switches, reloads and the sign-in
     redirect inside the same browser tab. It never outlives the browser session, which matters on shared
     office PCs with financial data.

Adoption for the screens the client named:

- **Contract wizard.**
  - With the workspace on, its open state stays in the URL (`?new=1`; `?draft=<leaseId>` once the Charges
    step has saved the server draft), so the tab reopens into the wizard.
  - Today `leases/page.tsx` strips `?new=1` on purpose, so that a reload or Back does not reopen the wizard
    (#91). With the workspace off, that stays exactly as it is. With it on, reopening is the point: the
    draft comes back with it.
  - `useTabDraft` holds every step's unsaved fields, including the cheque grid before "Save cheques". The
    server draft's `version` is the base.
- **Journal entry (new):** `useTabDraft` for date, property, narration and lines.
- **Vouchers:**
  - `useTabDraft` for all unsaved edits.
  - After the first save, the saved id goes into the URL (`?id=`) with `router.replace`. Today it lives only
    in component state (`savedId`), so the tab would reopen a blank form.
- **General Ledger, Trial Balance, Tenant Ledger, journals list, contracts list:** applied filters, view mode
  and page size move into the URL, where they are not there already.
- **Termination and settlement:** the builder audits them; at least `useUnsavedChangesWarning`.

**Known limit:** a form that has not adopted `useTabDraft` loses typed input when the user switches tabs.
`useUnsavedChangesWarning` does not help: it only covers leaving the document, and a tab switch does not leave
it. My call: Phase 2 lists every remaining form and adds a small `useTabDirty()`, which makes the host ask
before switching away from one. Forms move to `useTabDraft` as the pilot shows which ones matter.

### 6. Titles

- **Default title:** the nav label of the route, via `activeNav` and the nav model, e.g. "General Ledger" /
  "دفتر الأستاذ العام". Every screen gets a sensible title with no page change.
- **Record screens:** call `useTabTitle(text)` with what identifies the record: contract number and unit, JV
  number, tenant name.
- **Browser tab:** `document.title` becomes `"{title} — Miftah"` (`— مفتاح` in Arabic). This also fixes (c).
- **Stored titles:**
  - A label-only title is stored as its message key, so switching EN↔AR re-translates it.
  - A record title is stored as the last text shown, so the strip can draw it before the page loads. It is
    refreshed when the tab is visited.
  - All titles render inside `<bdi>`: Arabic names and Latin contract numbers mix.

### 7. Persistence and scope

- **The live workspace:** `sessionStorage` per browser tab.
  - It survives reloads and the sign-in redirect.
  - A second browser window is an independent workspace, like a second copy of PACT.
- **The snapshot:** a copy of the tab list goes to `localStorage` under
  `miftah.workspace.v1:{userId}:{orgId}`.
  - It seeds a browser tab that starts with no workspace: a new window, or the next morning.
  - Whether it restores automatically or asks first is client question Q4. Last writer wins; it is only a
    seed.
  - Only a browser tab that lands on the bare home page is seeded from it. A browser tab opened on a
    specific URL (Ctrl-click, a pasted link) starts a workspace holding just that tab, so it never
    duplicates the other window's tabs.
  - It holds hrefs and titles, never amounts or drafts.
- **On load, each stored tab is checked:**
  1. Moved URLs are rewritten through `legacyRedirect`.
  2. Unknown routes are dropped.
  3. Routes the user's role cannot reach (`DASHBOARD_ROUTES` + `routeAllows`) are dropped.
- **Cap:** 15 tabs (my call; client Q5). Opening a 16th asks the user to close one. A tab with unsaved input
  is never closed automatically.

### 8. Sign-in, organisation and role

- **Session expiry:** unchanged path. The fetch guard sees the proxy's 401 and sends the user to sign in,
  with `callbackUrl` set to the active tab's URL. After sign-in, the same browser tab reloads its workspace
  and drafts from `sessionStorage`, and every tab comes back, still marked unsaved where it was. Optimistic
  versions (e.g. the contract draft's `version`) still guard a stale draft.
- **Sign-out:** clears that user's snapshot and every `ws:{userId}:…` key in this browser tab.
- **Another user signing in on the same browser:** keys carry the user id, so they never see the previous
  user's workspace.
- **Organisation switch:**
  - The workspace is per organisation. `TenantSwitcher`'s hard navigation loads the other organisation's
    workspace, and switching back restores the first one.
  - Before switching, if any tab has unsaved input, the user is asked first.
  - A switch from another browser tab keeps today's blocking notice (F3). After the reload, this browser tab
    shows the new organisation's workspace.
- **Super admins:**
  - A super admin gets a workspace only inside an organisation whose flag is on.
  - Global View has no workspace: the strip is hidden.
  - The `/superadmin/*` pages are never tabs. Visiting one hides the strip and leaves the workspace in
    storage. `/superadmin` mounts its own `AuthenticatedLayout`, so the host must rebuild from storage
    whenever it mounts.
- **Role change:** the load-time check drops tabs the role no longer reaches. The route guard still protects
  every page as today.
- **Tenant isolation:** no tab, title or draft is ever shown under another organisation. The keys carry
  `orgId`, and pages still fetch through the guard, which stamps the organisation on every write.

### 9. Arabic (RTL)

- The strip starts at the start edge: right in Arabic.
- Logical CSS only: `ps`/`pe`, `start`/`end`, `border-inline-*`. Chevrons mirror with `rtl:-scale-x-100` as
  elsewhere.
- Arrow keys inside the tablist follow the visual order (WAI-ARIA APG): in Arabic, ArrowLeft moves to the
  next tab. "Next tab" shortcuts are logical: next means towards the end.
- Shortcuts match on `KeyboardEvent.code` (`Digit1`, `KeyW`, `PageDown`), not `key`, so they work with an
  Arabic keyboard layout.
- Every new string is added to `messages/en.json` and `messages/ar.json`.

### 10. Keyboard (proposal; client Q3)

A normal browser tab cannot override these keys: Ctrl+T, Ctrl+W, Ctrl+N, Ctrl+Tab, Ctrl+Shift+Tab,
Ctrl+1…9, Ctrl+PgUp/PgDn, Ctrl+F4. PACT's window keys can't be copied one for one.

| Keys | Action |
|---|---|
| Alt+1 … Alt+8, Alt+9 | Go to tab n; Alt+9 = last tab |
| Alt+PgDn / Alt+PgUp | Next / previous tab |
| Alt+W | Close tab (asks if unsaved) |
| Alt+Shift+T | Reopen last closed tab |
| Ctrl+K (exists) | Search and open a screen or record; Enter opens it in a tab |
| In the strip: ←/→, Home/End, Delete | Move between tabs (visual order), first/last, close the focused tab |

- macOS: Option+digit types characters (¡, ™), so the Mac bindings are the builder's call.
- The client's staff are on Windows. A help overlay (`?` button in the strip menu) lists the keys in EN/AR.

### 11. Performance

- Only the active page is mounted. Background tabs make no requests and hold no DOM, so 15 tabs cost a few
  KB of storage.
- Switching costs what a menu click costs today: one client navigation plus that page's own API calls.

**Measured on 2026-11-02.** Each figure is the time from `router.push` to the painted result, over 20 rounds
through the 9 screens. The accountant role was used, on a production web build at commit `12f918564`.

- **Data:** the local 8,000-unit dataset from the scale track: 23,350 contracts, 161,725 cheques, 589,714
  journals, 1.2 million journal lines.
- **Fast:** this Mac, with no slowdown.
- **Office-like:** 4× CPU slowdown, 40 ms latency and 20 Mbps. This is my estimate of a mid-range office laptop
  on a decent line.

| Screen | Fast p50 | Office-like p50 | Office-like p95 | Slowest server call (office-like p50) |
|---|---|---|---|---|
| New journal voucher | 0.03 s | 0.10 s | 0.13 s | none |
| Posted journal voucher | 0.06 s | 0.18 s | 0.21 s | 0.05 s |
| Journals list | 0.13 s | 0.21 s | 0.24 s | 0.08 s |
| Tenant Ledger (18 rows) | 0.11 s | 0.25 s | 0.28 s | 0.05 s |
| Contract (General section) | 0.09 s | 0.32 s | 0.33 s | 0.05 s |
| Contracts list | 0.27 s | 0.34 s | 0.52 s | 0.20 s |
| General Ledger, one account, ~820 rows | 0.33 s | 1.11 s | 1.50 s | 0.15 s |
| Trial Balance (573 rows) | 1.21 s | 1.77 s | 2.10 s | 1.58 s |
| General Ledger, largest account, ~2,200 rows | 0.76 s | 2.62 s | 3.18 s | 0.25 s |

What this shows:

- **The 1 s target (p95):** 6 of the 9 screens meet it. The General Ledger and Trial Balance miss it.
- **General Ledger:** the time is the browser, not the server. Most of it is React rendering every row, and
  about 0.8 s of the 2.6 s is layout and paint. `table-layout: fixed` made no difference when tested (2.51 s
  vs 2.62 s). The fix is to draw fewer rows at once, by paging or by rendering only the rows on screen. The
  catch is that browser Ctrl+F then cannot find rows that are not drawn (client Q9).
- **Trial Balance:** the time is the server (about 1.6 s), adding up the whole organisation's lines. The fix
  is on the backend.
- **Feedback:** the screen starts changing within about 0.15 s, and the strip can highlight the tab at once.
- **First open:** the first visit in a session also loads that screen's code. The ledgers then took 2–4 s, the
  others under 1.5 s.
- **Memory:** JavaScript heap stayed between 19 and 58 MB across the 20 rounds, with no upward trend.

Limits of these numbers:

- The backend and database ran on this Mac, not the production VM, so server times there may differ.
- One user at a time, while the machine was also busy with other work (load average 5–8).
- The tab strip is not built. `router.push` is the call it would make, but draft and scroll restore are not
  included.
- Production was not measured.

**Phase 1 exit check:** re-measure with the real strip, on the production VM if possible.

### 12. Phones, tablets and roles

- **Flutter apps:** untouched.
- **Below 768 px:** the strip is hidden and the workspace is off. Links behave exactly as today.
- **768–1279 px:** a compact strip (icon and short title).
- **Roles:** staff roles only (tenant admin, property manager, accountant, tenant user, super admin inside an
  organisation with the flag on). Renter, security guard and promoter users never see tabs; their web is a
  handful of pages.

### 13. Rollout

- A tenant feature `WORKSPACE_TABS` (default off), the same way `MOBILE_FINANCE` is done: the
  `TenantFeature` enum and its label in `TenantFeatureService`.
- **"Workspace on"** means: the flag is on for the organisation, the role is a staff role, the screen is at
  least 768 px wide, and the page is not a `/superadmin` page.
- **Phase 2 is gated too (my call).** URL view-state moves, `useTabDraft` restores and the wizard's
  `?new=1` all act only while the workspace is on.
  - With it off, every screen behaves exactly as today and no existing test changes.
  - `useTabDraft` is a no-op without a tab id.
- Pilot on the client's organisation, then default on after feedback.
- **Default on is a separate decision with its own gate.** The strip's `role="tab"` buttons and screen titles
  can collide with existing locators: `getByRole('tab')` in 6 specs and about 70 `getByText` calls.
  - Before default-on, those locators are scoped to `<main>`, and the strip carries its own `data-testid`.
  - Until then, test organisations keep the flag off.
  - That sweep is sized when default-on is decided.
- The builder confirms that adding an enum value needs no Liquibase changeset. If it does, the next free
  number applies (check the changesets folder; at least 185).

### 14. Testing

- **Vitest:**
  - Workspace store: open, focus, close, reopen, cap, load-time validation including `legacyRedirect` and
    role drops.
  - Identity matching per route: vouchers `?id=`, tenant ledger `?renterId=`, pinned views, drill-downs.
  - The URL ↔ tab rules, including fresh loads that land on home after an organisation switch or a sign-in.
  - `useTabDraft`: write, restore, clear, and keys per user, organisation and identity. Also the "record
    changed since your edits" choice, and the no-op when the workspace is off.
  - The keyboard map, using `code` under an Arabic layout.
  - RTL arrow order.
- **Playwright, EN and AR, flag on for one test organisation:**
  - Open contract, JV, GL and tenant ledger; type into the JV; switch around; the JV is intact.
  - Closing a dirty tab asks first.
  - Reload restores everything.
  - Clearing the session cookie, then signing in again, restores everything.
  - An organisation switch swaps the workspace.
  - A role-limited tab is dropped.
  - Back/Forward land on the right tab.
  - Two tabs on the same route with different filters each show their own data.
- **Flag off:** existing suites and the tutorial recorder run unchanged.

## Phased plan

| Phase | What ships | Size |
|---|---|---|
| **0: Browser-tab hygiene** (no flag, can ship now) | Per-page `document.title` from the nav model and record pages. Record links and cards become real links, so Ctrl/middle-click opens a browser tab. `useUnsavedChangesWarning` on the JV form, the contract wizard and the voucher forms. | S, ~2–3 days |
| **1: Workspace strip** (behind `WORKSPACE_TABS`) | Store, strip, menu, cap, per-route `identity` in `routeRegistry`, opening rules, `WorkspaceLink` adopted on the nav panel and on record links in the named screens, URL ↔ tab rules (fresh-load rules, plus a `callbackUrl` on the layout's sign-in redirect), persistence and validation, titles, scroll restore, keyboard, RTL, sign-out and org-switch handling. Page keyed per tab. Re-measure switch time. | M–L, ~1.5–2 weeks |
| **2: Tab memory for the named screens** (acts only while the workspace is on) | URL state for contracts list, GL, trial balance, tenant ledger and journals list. `useTabDraft` with version check in JV, contract wizard (all steps, + URL open state) and vouchers (+ `?id=` after first save). Termination and settlement audit, list of remaining forms with `useTabDirty()`. Dirty dots and confirmations. **Speed:** General Ledger draws fewer rows at once; faster Trial Balance query. | L, ~2.5–3 weeks |
| **Pilot** | Flag on for the client's organisation; collect feedback against the open questions | — |
| **3: Only if the pilot asks** | Read-only side "peek" for a voucher or ledger (the useful part of (d)). Instant switch for the last few tabs (narrow keep-alive or a GET cache). Tab context menu (duplicate, pin, close to the right) and drag to reorder. | Sized then |

Each phase follows the usual loop: plan → review → merge → deploy.

## Open questions for the client

1. **Opening a contract from the list:** should it open in its own tab (as PACT does, our proposal), or
   replace the list in the same tab?
2. **The same screen twice:** do your staff keep two copies of the same screen open with different filters
   (e.g. the General Ledger for two buildings)? Our proposal: the menu focuses the one already open, and a
   second copy needs "Duplicate tab" (Phase 3).
3. **Shortcuts:** which PACT keys do your staff use every day (switch window, close window, save, post, new
   voucher, find)? Browsers keep Ctrl+Tab, Ctrl+W and Ctrl+1…9 for themselves; we propose Alt-based keys.
4. **Next morning:** when someone closes the browser and comes back the next day, should yesterday's tabs
   reopen by themselves, be offered, or start empty? Half-typed entries are not kept past closing the
   browser. Are your PCs shared between staff?
5. **How many:** how many screens does a busy accountant keep open at once? (We propose a cap of 15.)
6. **Side by side:** does anyone need two screens visible at the same time, e.g. the contract next to the
   tenant ledger, rather than switching? On what screen sizes? A second browser window already allows this.
7. **Who:** accountants only, or the operations staff too?
8. **Tablets:** is the web app used on tablets in the field?
9. **Long ledgers:** do your accountants use Ctrl+F to search a whole ledger on screen? It decides how we make
   big ledgers faster: pages of rows with a server-side search, or drawing only the rows on screen.

## Decisions for Kunal (before the implementation plan)

- Approve the recommendation: (b) with tab memory, Phase 0 first; no (a), no (d) for now.
- Ship Phase 0 on its own now, without waiting for the client's answers?
- Pilot behind the `WORKSPACE_TABS` tenant flag (one small backend change), or a web-only switch?
- Send Q1–Q9 to the client as written, or trim?

## Calls made in this design (check these)

- `sessionStorage` for the live workspace and drafts; a `localStorage` snapshot for tab lists only, used to
  seed home landings only.
- Menu items focus an open copy rather than opening a second one; record links open next to the current tab.
- Tab identity = path + per-route identity parameters. Links that carry a query focus only an exact match, so
  a drill-down never overwrites a ledger the user has set up.
- Drafts cover every unsaved edit, including edits after the first server save, with a version check on
  restore. The alternative, asking before every switch away from a dirty form, breaks the free switching
  the client asked for.
- A fresh load that lands on home restores the stored active tab instead of overwriting it.
- All of Phase 2 acts only while the workspace is on. Default-on is a later decision with a locator sweep.
- Global View and `/superadmin` pages have no tabs.
- Links that have not opted in keep today's behaviour (navigate in place).
- Switching tabs pushes a history entry, so Back goes to the previous tab.
- Cap of 15 tabs; tabs with unsaved input are never closed automatically.
- Alt-based shortcuts, matched on `KeyboardEvent.code`.
- The page is keyed per tab, so every switch remounts it.
- Forms not yet adopted get a "leave this form?" check on tab switch rather than losing input silently.
- Big-ledger and Trial Balance speed work goes into Phase 2, because they miss the 1 s target.
- Workspace off below 768 px and for renter, guard and promoter users.
- `cacheComponents` stays off; keep-alive is revisited only if Phase 1's measurement fails.

## Left to the builder

- The tab id format.
- The debounce timings.
- The scroll-restore retry details.
- The help overlay's look.
- Mac shortcuts.
- Exactly which `router.push` sites become `WorkspaceLink`s beyond the named screens.
- The draft size limit (suggest 200 KB per draft; on a quota error, drop the oldest drafts of closed tabs
  first).
