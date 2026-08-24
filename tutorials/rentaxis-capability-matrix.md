# RentAxis capability and tutorial matrix

Status legend:

- **Prod E2E** — exercised against the production environment by an automated test.
- **Local E2E** — covered by a browser integration test against the local stack.
- **Mobile integration** — covered by a Flutter integration test.
- **Unit/widget** — covered below the end-to-end level.
- **Audit required** — present in the product but still needs an end-to-end production pass.

## Shared foundation

| Tutorial | Surfaces | Roles | Capabilities | Current evidence |
|---|---|---|---|---|
| 01. Sign in and navigate RentAxis | Web, Manager, Renter, Security | All | Sign in, sign out, password setup/change, sidebar/bottom navigation, profile, English/Arabic switching | Prod E2E (auth); production UI journey prepared for locale/profile/password/logout/re-login; Local E2E; Mobile integration |
| 02. Dashboard, search, notifications, and help | Web, Manager, Renter | All | KPI dashboard, global search, alerts, notification center, help articles, guided tours | Prod E2E (notifications); read-only production UI spec prepared for command-palette lease navigation, Security Guard help detail, and Super Admin follow-ups; pending #100/#101/#102 deployment; Local E2E (dashboard) |
| 03. Roles, permissions, and organization switching | Web | Super admin, tenant admin, property manager, renter | Role boundaries, tenant switcher, role-specific navigation | Production E2E spec prepared (feature-admin and role-escalation boundaries); production run pending safety deployments; Local E2E (RBAC) |

## Administration and portfolio

| Tutorial | Surfaces | Roles | Capabilities | Current evidence |
|---|---|---|---|---|
| 04. Provision organizations and manage feature access | Web | Super admin | Create/edit organizations, organization switcher, feature toggles for Listings, Meetings, Email Notifications, Lease Renewals, and Gate Pass | Prod E2E (create/switch); production E2E toggle round-trip prepared with guaranteed restoration; local E2E |
| 05. Create users and staff | Web, Manager | Super admin, tenant admin | Create/update/delete users, assign roles, property assignments, staff profiles, activate/deactivate staff | Production E2E user CRUD/property-assignment/role-boundary spec prepared; production run pending #105; staff lifecycle already prepared; manager widget tests |
| 06. Create a project and property portfolio | Web, Manager | Tenant admin, property manager | Projects, properties, English/Arabic names, type, emirate, address, Makani, expenses, card/table views | Prod E2E (property); Local E2E; production UI audit required |
| 07. Buildings, units, contacts, amenities, and parking | Web, Manager | Tenant admin, property manager | Buildings/floors, unit types/rents/status, property contacts, amenities, parking spots | Prod E2E (building/unit/contact/amenity/parking lifecycle); Local E2E |
| 08. Import a property portfolio in bulk | Web | Tenant admin | Download template, upload portfolio, validate rows, monitor import, verify created entities | Prod E2E |
| 09. Manage renters and portal access | Web, Manager | Tenant admin, property manager | Renter profiles, bilingual names, contacts, preferred language, portal account creation | Prod E2E (API and UI); Local E2E |

## Leasing and renewals

| Tutorial | Surfaces | Roles | Capabilities | Current evidence |
|---|---|---|---|---|
| 10. Draft a lease and preview its payment plan | Web | Tenant admin, property manager | Parties, dates, rent/deposit, VAT and charges, installment strategy, schedule preview, draft creation | Prod E2E |
| 11. Generate, review, and sign a tenancy contract | Web, Renter | Tenant admin, property manager, renter | Contract preview/generation, authenticated PDF download, renter rejection, clean regeneration, renter acceptance | Production E2E spec prepared; production run pending #95/#104 deployment; focused backend tests pass |
| 12. Activate and administer a lease | Web, Manager | Tenant admin, property manager | Activation, metadata edits, attachments, interaction notes, payment schedule edits | Prod E2E (activation/interactions); production spec prepared for metadata edits, payment-plan edits, and safe draft deletion; attachment audit still required |
| 13. Renew, extend, terminate, and settle a lease | Web, Manager, Renter | Super admin, tenant admin, property manager, renter | Renewal opportunities, reminders, renter intent, extension, termination, deductions, settlement preview/finalization | Local E2E spec prepared (scoped scan/reminder/intent/close + extension/settlement); production run pending #95/#99 deployment; manager widget coverage |
| 14. Penalties and cheque-failure fines | Web, Manager, Renter | Tenant admin, property manager, renter | Fine settings, penalty calculation, penalty review/payment state | Local E2E spec prepared (reason-specific fines, renter view, receipt, waiver); production run pending #95 deployment; unit/widget coverage |

## Payments and finance

| Tutorial | Surfaces | Roles | Capabilities | Current evidence |
|---|---|---|---|---|
| 15. Manage rent cheques end to end | Web, Manager | Tenant admin, property manager | Pending, collect, deposit, clear, bounce, notes, cheque image extraction, bulk cheque upload | Prod E2E (collect/deposit/extraction); production specs prepared for clear/failure/receipt plus synthetic bulk attachment without a real file; production rerun pending safety deployments; bulk upload UI audit still required |
| 16. Configure online rent collection | Web, Manager | Super admin, tenant admin | Payment gateway configuration, rent settings, online-payment readiness | Local E2E spec prepared (fake test config, secret masking, renter read, deactivation); production run pending #95; manager widget tests |
| 17. Chart of accounts and account mappings | Web, Manager | Super admin, tenant admin | Accounts CRUD, hierarchy, mapping operational events to ledger accounts | Local E2E spec prepared (seed/list/mapping); production run pending #95; manager widget tests |
| 18. Record and review financial transactions | Web, Manager | Super admin, tenant admin | Income/expense entries, property/unit scoping, references, transaction list | Local E2E spec prepared; production run pending #95 |
| 19. Vendors and bank accounts | Web, Manager | Super admin, tenant admin | Vendor CRUD/details, bank accounts, property association | Prod E2E (vendor API); manager widget tests; production UI audit required |
| 20. Dashboards and financial reports | Web, Manager | Super admin, tenant admin | Collection KPIs, occupancy, income statement, balance sheet, NOI and report details | Prod E2E (report APIs); Local E2E; manager widget tests |
| 21. Renter payments | Web, Renter | Renter | Payment schedule, online payment widgets, payment history and receipts | Prod E2E; Local E2E; renter integration coverage |

## Service operations

| Tutorial | Surfaces | Roles | Capabilities | Current evidence |
|---|---|---|---|---|
| 22. Submit and manage maintenance tickets | Web, Manager, Renter | Tenant admin, property manager, renter | Ticket creation, assignment, status/history, attachments, renter closure OTP, ticket reports | Prod E2E (create/assign/estimate/reply/status/OTP close/rating/history/reports); attachment audit required; renter/manager widget coverage |
| 23. Schedule and manage meetings | Web, Manager, Renter | Tenant admin, property manager, renter | Create meeting, property/unit context, details, status and participant views | Local E2E lifecycle spec prepared; production run pending #95; mobile integration/deep-route coverage |
| 24. Facilities and booking approvals | Web, Manager, Renter | Tenant admin, property manager, renter | Facilities, availability, booking requests, renter requests, manager approvals | Local E2E amenity/parking booking spec prepared; production run pending #95; manager/renter widget coverage |
| 25. Gate passes and walk-in visitors | Web, Manager, Renter, Security | Tenant admin, property manager, renter, guard | Resident passes, guest QR, approvals, scan/admit, walk-ins, access policy, guard/vendor management, reports | Local E2E resident-pass/approval/entry/exit/report spec prepared; production run pending #95; walk-in/policy audit required; mobile integration coverage |

## Marketplace and engagement

| Tutorial | Surfaces | Roles | Capabilities | Current evidence |
|---|---|---|---|---|
| 26. Publish and manage property listings | Web, Manager | Tenant admin, property manager | Listing creation/editing, media/details, publish/unpublish, interests | Local E2E lifecycle spec prepared; production run pending #95; manager widget coverage |
| 27. Browse the marketplace and manage a wishlist | Web, Renter | Renter/public | Browse/filter listings, listing details, wishlist, express interest | Local E2E browse/detail/wishlist/interest spec prepared; production run pending #95; renter widget/integration coverage |
| 28. Promotions, offers, and coupons | Web, Manager, Renter | Tenant admin, renter | Promotion CRUD, targeting, image/copy, renter feed, offer details, coupon actions | Local E2E business/ad/targeting/feed/event/statistics spec prepared; production run pending #95; renter widget coverage |

## Mobile app-specific journeys

| Tutorial | Surface | Roles | Capabilities | Current evidence |
|---|---|---|---|---|
| 29. Manager mobile essentials | Manager mobile | Tenant admin, property manager | Dashboard, properties, renters, leases, payments, tickets, notifications, profile | Mobile integration and widget coverage; broader production pass required |
| 30. Manager mobile operations | Manager mobile | Tenant admin, property manager | Finance, reports, vendors, staff, settings, meetings, facilities, listings, gate pass administration | Partial mobile integration/widget coverage |
| 31. Renter mobile essentials | Renter mobile | Renter | Home, lease, payments, penalties, tickets, notifications, profile | Mobile integration and widget coverage |
| 32. Renter mobile services | Renter mobile | Renter | Browse/wishlist, promotions, meetings, facilities, gate passes and approvals | Mobile integration and widget coverage |
| 33. Security app | Security mobile | Guard/security | Phone/OTP login, QR scanning, result/admission, approvals, walk-ins and status | Mobile integration and widget coverage |

## Coverage summary

- Web routes inventoried: public/auth, dashboard, properties, renters, leases, settlement, finance, staff, settings, tickets, listings, meetings, bookings, gate pass, promotions, notifications, profile, help, marketplace, super-admin.
- Feature flags inventoried: Listings, Meetings, Email Notifications, Lease Renewals, Gate Pass.
- The persistent `RentAxis Tutorial Demo` tenant currently has all five feature flags disabled; enabling them is required before recording tutorials 13, 23, 25, 26, 27, and notification-delivery portions of tutorial 02.
- Production functional baseline: 14/14 workflows passed, including the new ticket and property-operations journeys. Cleanup exposed an email outbox row lock; the exact TEST-E2E tenant was subsequently removed and verified absent.
- Bug fix PR: [#95](https://github.com/kunal-ak23/rentaxis/pull/95) prevents Azure email delivery calls from holding database transactions and blocking tenant cleanup.
- Coverage PR: [#96](https://github.com/kunal-ak23/rentaxis/pull/96) now contains 31 serial scenarios and prepares production coverage for finance/settings, cheque failures and penalties, draft metadata/payment-plan editing, synthetic bulk cheque attachment, facilities/bookings, gate passes, promotions, notification ownership, renewals, payment-gateway configuration, contract rejection/regeneration/acceptance, extended lease lifecycle, listings/marketplace, meetings, tickets, and property operations.
- Security fix PR: [#98](https://github.com/kunal-ak23/rentaxis/pull/98) scopes mark-notification-read to the authenticated owner; focused Gradle tests pass.
- Renewal safety PR: [#99](https://github.com/kunal-ak23/rentaxis/pull/99) adds a tenant-scoped super-admin renewal scan so verification cannot process unrelated opted-in customers; focused Gradle tests pass.
- Help-content PR: [#100](https://github.com/kunal-ak23/rentaxis/pull/100) adds the shipped Security Guard role and gate duties to in-product guidance.
- Dashboard fix PR: [#101](https://github.com/kunal-ak23/rentaxis/pull/101) allows Super Admins administering a tenant to load renewal follow-ups; focused controller security tests pass.
- Search PR: [#102](https://github.com/kunal-ak23/rentaxis/pull/102) replaces the inert header placeholder with a role-aware command palette for leases, cheques/payments, and organizations; component tests, TypeScript, and the production build pass.
- Contract safety PR: [#104](https://github.com/kunal-ak23/rentaxis/pull/104) cleans exact tenant contract artifacts only after database commit, guards container/local-path ownership, replaces rejected PDFs cleanly, and removes a newly uploaded PDF on transaction rollback.
- User-authorization PR: [#105](https://github.com/kunal-ak23/rentaxis/pull/105) applies tenant/role target authorization to user deletion and property-assignment add/read/removal; cross-tenant controller integration tests pass.
- Main-baseline rework: all open tutorial PRs (#95, #96, #98–#102, #104, #105) are based on or rebased onto `origin/main` at `42c398f` (including signed identity phase 1, #103), remain open, and report clean merge state.
- Safe full production rerun order: deploy #95, #98–#102, #104, and #105, then explicitly approve, implement, test, and deploy exact cleanup for non-contract uploaded artifacts before executing #96 against a disposable TEST-E2E tenant. The real cheque-upload scenario creates an Azure blob that tenant deletion does not currently remove. No gated feature was changed on the persistent tutorial tenant.
- Tutorial plan: 33 narrated videos, with shorter capability chapters where a workflow has multiple independent actions.
- Narration deliverable: [tutorial-storyboards.md](./tutorial-storyboards.md) contains audience, capture actions, and voiceover copy for all 33 videos plus the recording acceptance checklist.
- Audio/video toolchain: [render-tutorial.sh](./render-tutorial.sh) synthesizes narration with macOS `say`, normalizes it to -16 LUFS, and produces a 1080p H.264/AAC MP4 with `ffmpeg`; shell syntax validation passes.
- Audio source tracks: [extract-narrations.mjs](./extract-narrations.mjs) reproducibly generates exactly 33 renderer-ready text tracks from the reviewed storyboard narration blocks.
