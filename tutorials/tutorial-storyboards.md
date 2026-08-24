# RentAxis narrated tutorial storyboards

These storyboards are the recording contract for the tutorial library. Record
at 1920×1080, browser zoom 100%, English UI, and a clean disposable demo tenant.
Use only `Tutorial Demo` names and synthetic contact details. Pause briefly
after every saved state so the viewer can confirm the result.

Audio production: record the silent Playwright/browser capture first, generate
the narration as an AIFF track with macOS `say`, then normalize and mux it with
`ffmpeg`. Keep narration around -16 LUFS integrated and leave UI audio muted.
Arabic-localization segments remain narrated in English unless an Arabic voice
track is commissioned separately.

## 01 — Sign in and navigate RentAxis

- Audience: all users; 2–3 minutes.
- Capture: sign in, identify the role-specific sidebar, switch EN → AR → EN,
  open My Profile, update the phone number, log out, and sign in again.
- Narration: “Welcome to RentAxis. Sign in with the account supplied by your
  administrator. The navigation automatically reflects your role, so you only
  see the work available to you. Use EN and AR to change language and reading
  direction. Your profile menu contains account details, password controls,
  and Logout. Always sign out on a shared device.”

## 02 — Dashboard, search, notifications, and help

- Audience: all web users; 3 minutes.
- Capture: explain KPIs, open the command palette with Ctrl/Cmd+K, find a lease,
  open notifications, mark one read, open Help Center, and search an article.
- Narration: “The dashboard summarizes the work that needs attention. Global
  search finds leases, renters, cheques, and—when you are a super admin—tenant
  organizations. Notifications link directly to their related record. The Help
  Center contains role-aware guides and interactive tours; search here whenever
  you need the exact steps for a task.”

## 03 — Roles, permissions, and organization switching

- Audience: super admins and organization admins; 3 minutes.
- Capture: show Super Admin, Tenant Admin, Property Manager, Tenant User,
  Renter, and Security Guard scopes; switch the active organization; compare
  navigation using prepared accounts.
- Narration: “RentAxis uses roles to keep each workflow and tenant isolated.
  Super admins provision organizations. Tenant admins manage an organization,
  property managers operate assigned properties, renters use resident services,
  and security guards handle gate access. Switching organizations changes the
  active data context; it does not copy or combine customer data.”

## 04 — Provision organizations and manage feature access

- Audience: super admins; 3–4 minutes.
- Capture: create a synthetic organization, enter address/TRN/phone, save,
  switch into it, explain all five toggles, enable the required demo features.
- Narration: “Create an organization from Super Admin, complete its legal and
  contact information, and save. Feature access is deliberately opt-in. Listings
  enables marketplace publishing, Meetings enables scheduling, Email
  Notifications enables delivery, Lease Renewals enables reminder automation,
  and Gate Pass enables resident and security workflows. Enable only features
  included in the customer plan.”

## 05 — Create users and staff

- Audience: super admins and tenant admins; 4 minutes.
- Capture: create a property manager, assign a property, edit the profile,
  demonstrate role restrictions, remove the assignment, then show staff record
  creation and activation status.
- Narration: “Users control application access; staff records describe people
  employed at a property. Choose the lowest role that matches the work, then
  assign property managers only to the properties they operate. RentAxis blocks
  tenant admins from creating super admins or administering another tenant.
  Update assignments when responsibilities change and deactivate accounts that
  should no longer sign in.”

## 06 — Create a project and property portfolio

- Audience: tenant admins and property managers; 4 minutes.
- Capture: create a project/property, add bilingual names, type, emirate,
  address, Makani number and fixed expenses; switch card/table views.
- Narration: “Properties are the foundation for units, leases, tickets, and
  reporting. Enter both English and Arabic names when available, then select
  the correct property type and emirate. Address and Makani data help operating
  teams identify the site, while fixed expenses support financial reporting.
  Use card or table view depending on the size of the portfolio.”

## 07 — Buildings, units, contacts, amenities, and parking

- Audience: tenant admins and property managers; 5 minutes.
- Capture: add a building and floor count, create a unit, add an emergency
  contact, create a bookable amenity and parking spot, edit each, then explain
  deactivation versus deletion.
- Narration: “A property can contain buildings, units, contacts, amenities, and
  parking inventory. Unit status drives availability throughout leasing and
  resident services. Contacts make operational numbers easy to find. Mark an
  amenity or parking spot as bookable when residents can request time or access.
  Deactivate historical inventory instead of erasing records needed for audit.”

## 08 — Import a property portfolio in bulk

- Audience: tenant admins; 4 minutes.
- Capture: download the current template, show required columns, upload a
  synthetic workbook, review validation errors, correct them, and verify the
  resulting property/unit/renter/lease counts.
- Narration: “Bulk import is designed for initial migration, not casual edits.
  Always download a fresh template so the columns match the running version.
  Validate the workbook before import and correct every reported row. After a
  successful run, compare the import summary with the source totals and inspect
  representative leases and cheque schedules.”

## 09 — Manage renters and portal access

- Audience: tenant admins and property managers; 3 minutes.
- Capture: create a renter with bilingual name, email, phone and language,
  enable the portal account, show the generated onboarding credential, edit the
  profile, and explain secure delivery.
- Narration: “A renter profile connects the resident to leases and services.
  Confirm email and phone carefully because they are used for portal access and
  notifications. Portal account creation generates an onboarding credential;
  share it through an approved secure channel and ask the resident to replace
  it. Preferred language controls communication where translations exist.”

## 10 — Draft a lease and preview its payment plan

- Audience: tenant admins and property managers; 5 minutes.
- Capture: complete all five wizard steps: parties, terms, charges, payment
  plan, and final review; save as draft and open the lease.
- Narration: “Choose a vacant unit and the correct renter first. Enter the lease
  dates, monthly rent, deposit, tax treatment, and any recurring or one-time
  charges. Select the number and distribution of installments, then review the
  generated schedule before saving. A draft can still be corrected and does not
  occupy the unit until it is accepted or activated.”

## 11 — Generate, review, and sign a tenancy contract

- Audience: tenant admins and renters; 4 minutes.
- Capture: preview the PDF, generate the contract, download it, sign in as the
  renter, reject with the resulting Draft state, regenerate, verify one current
  PDF, then accept.
- Narration: “Preview lets the administrator verify the agreement without
  creating the final document. Generate moves the lease to Pending Signature
  and makes the PDF available to the renter. A rejection returns the lease to
  Draft for correction; regeneration replaces the rejected document. Acceptance
  activates the lease, occupies the unit, and creates the payment schedule.”

## 12 — Activate and administer a lease

- Audience: tenant admins and property managers; 4 minutes.
- Capture: activate an eligible lease, edit allowed metadata, add an interaction
  note, show the event history and payment schedule, and explain attachments.
- Narration: “Activation begins the operational lease lifecycle. Keep metadata
  such as Ejari references and payment details current, and record calls or
  follow-ups as interactions so the team shares one history. The event timeline
  shows major state changes. Attachments should contain only relevant tenancy
  documents and must never include unrelated customer information.”

## 13 — Renew, extend, terminate, and settle a lease

- Audience: admins, property managers, and renters; 6 minutes.
- Capture: open a renewal opportunity, send a reminder, capture renter intent,
  close/mark renewed, extend an active lease, preview settlement, add a deduction
  and credit, save draft, then finalize termination.
- Narration: “Renewal opportunities appear inside the configured notice window.
  Record each reminder and the renter response so follow-ups remain auditable.
  Extension changes the end date without closing the lease. For move-out, review
  unpaid rent, penalties, deposit, deductions, and credits before saving the
  settlement draft. Finalization terminates the lease and should be performed
  only after the financial amounts have been approved.”

## 14 — Penalties and cheque-failure fines

- Audience: finance operators and renters; 4 minutes.
- Capture: configure fine settings, fail a cheque for each supported reason,
  show the generated penalty, renter visibility, payment state, receipt, and an
  authorized waiver.
- Narration: “Fine settings define consistent treatment for bounced cheques,
  signature mismatch, closed accounts, and late days. Select the real failure
  reason because it determines the penalty. The renter can see open and paid
  penalties. Record payment with its receipt, or waive only when authorized and
  include a clear note for the audit trail.”

## 15 — Manage rent cheques end to end

- Audience: tenant admins and property managers; 5 minutes.
- Capture: inspect pending installments, attach cheque details/image, collect,
  deposit, clear, download receipt, demonstrate failure, notes, and bulk attach.
- Narration: “Each installment progresses through a controlled cheque lifecycle.
  Confirm the cheque number, bank, amount, and due date before collection.
  Deposit records handoff to the bank; clear confirms settlement. A failure must
  capture its reason and may create a penalty. Bulk attach accelerates migration,
  but the result summary must be reconciled before moving on.”

## 16 — Configure online rent collection

- Audience: super admins and tenant admins; 4 minutes.
- Capture: open gateway settings, select a provider/test mode, enter synthetic
  keys, save, demonstrate masking and renter readiness, then deactivate.
- Narration: “Online collection requires an approved gateway configuration.
  Begin in test mode and use provider-issued credentials; secrets are masked
  after save and are never displayed back to users. Verify the renter payment
  surface before enabling live mode. Deactivate the configuration immediately
  if credentials are rotated or the provider is unavailable.”

## 17 — Chart of accounts and account mappings

- Audience: finance admins; 4 minutes.
- Capture: inspect seeded accounts, add a child account, show hierarchy, map a
  rent/expense event, edit the mapping, and explain downstream ledger use.
- Narration: “The chart of accounts organizes every financial transaction.
  Preserve the parent-child structure and use consistent account codes. Account
  mappings tell RentAxis where operational events post in the ledger. Review
  mappings before recording live transactions because changing them affects
  future postings, not the historical meaning of existing entries.”

## 18 — Record and review financial transactions

- Audience: finance admins; 4 minutes.
- Capture: create income and expense entries, select property/unit scope, add a
  reference and description, filter the transaction list, and open details.
- Narration: “Use manual transactions for approved items that are not generated
  by a lease workflow. Choose income or expense, the correct ledger account,
  date, property and—when relevant—unit. A meaningful reference and description
  make reconciliation possible. Use filters to review a period or property and
  compare totals with the source documents.”

## 19 — Vendors and bank accounts

- Audience: tenant admins; 4 minutes.
- Capture: create/edit a vendor, open details, add a property bank account,
  choose the default, update branch information, and explain deactivation.
- Narration: “Vendor records centralize the companies paid for property work.
  Keep contact and tax details accurate. Bank accounts are associated with the
  receiving property and currency; only one appropriate account should be the
  default for a given workflow. Deactivate obsolete records after confirming no
  active process still references them.”

## 20 — Dashboards and financial reports

- Audience: super admins and tenant admins; 4 minutes.
- Capture: explain collections and occupancy KPIs, open income statement,
  balance sheet and NOI views, change period/property filters, inspect details.
- Narration: “Dashboard KPIs are operational signals, while reports provide the
  accounting breakdown. Select the same period and property scope before
  comparing reports. Use drill-down details to explain unusual totals and
  reconcile them with transactions and payment schedules. Export or share only
  through approved customer channels.”

## 21 — Renter payments

- Audience: renters; 3 minutes.
- Capture: sign in as a renter, open schedule/history, inspect status and due
  dates, open a receipt, and show online payment when a gateway is active.
- Narration: “The Payments area shows each installment, its due date, method,
  and current status. Use the receipt for cleared payments and contact the
  property team if an amount or status is incorrect. Online payment is available
  only when the organization has an active gateway; complete the provider flow
  once and wait for confirmation instead of submitting repeatedly.”

## 22 — Submit and manage maintenance tickets

- Audience: renters and operations teams; 6 minutes.
- Capture: renter creates ticket; manager assigns vendor/staff, adds estimate and
  reply, changes status, renter confirms OTP closure and rating; open history and
  reports.
- Narration: “Describe the issue, location, priority, and safe supporting images.
  The operations team assigns ownership, records estimates and communicates in
  the ticket thread. Status should reflect the real work stage. When OTP closure
  is enabled, the renter confirms completion before closure and can rate the
  outcome. History and reports preserve accountability.”

## 23 — Schedule and manage meetings

- Audience: admins, property managers, and renters; 4 minutes.
- Capture: create a meeting with property/unit context, participant and time;
  show renter view, update status/details, then cancel/complete.
- Narration: “Meetings keep property conversations connected to the relevant
  tenant, property, unit, and participants. Choose an accurate time and purpose,
  then confirm that the renter can see it. Update status when plans change so
  dashboards and notifications remain reliable. Use Cancelled or Completed
  rather than silently deleting historical appointments.”

## 24 — Facilities and booking approvals

- Audience: renters and property managers; 5 minutes.
- Capture: create a bookable amenity and parking spot, renter requests each,
  manager approves with note, renter cancels one, release the completed booking.
- Narration: “Facilities and parking inventory determine what residents can
  request. The renter selects an available period and submits a booking. Managers
  review conflicts and policy before approval and add a useful note. Cancellation
  releases a future reservation, while Release marks allocated parking or a
  completed booking as available again.”

## 25 — Gate passes and walk-in visitors

- Audience: renters, managers, and security guards; 6 minutes.
- Capture: create resident guest pass, manager approval, guard assigned-property
  view, QR/code scan, admit and exit, walk-in registration, policy and report.
- Narration: “A resident pass records the visitor, visit window, unit, vehicle,
  and purpose. Approval makes it available to the assigned property guard. Scan
  the QR code or enter the numeric code, verify the displayed visitor details,
  then record entry and exit. Walk-ins require the same identity and unit checks.
  Never admit a visitor from a screenshot without validating the live result.”

## 26 — Publish and manage property listings

- Audience: tenant admins and property managers; 5 minutes.
- Capture: create a listing from a vacant unit, add details and media, preview,
  publish, inspect interests, unpublish, edit, and explain delete.
- Narration: “Listings expose selected vacant-unit information to the marketplace.
  Confirm price, availability, amenities and contact details, then upload only
  approved property media. Preview before publishing. Interests appear in the
  listing workspace for follow-up. Unpublish when temporarily unavailable;
  delete only a listing that should not remain in history.”

## 27 — Browse the marketplace and manage a wishlist

- Audience: renters and public visitors; 4 minutes.
- Capture: browse tenant marketplace, filter, open details, add/remove wishlist,
  express interest with note, and show the manager-side interest.
- Narration: “Use filters to narrow listings by the details that matter, then
  open a listing for full information. Signed-in renters can save a property to
  the wishlist and express interest. Add a concise note when requesting contact.
  Removing a wishlist item does not cancel an already submitted interest.”

## 28 — Promotions, offers, and coupons

- Audience: tenant admins and renters; 5 minutes.
- Capture: create a business and ad, add copy/image/CTA/coupon, target property,
  activate, show renter feed/detail, record click/coupon, inspect statistics.
- Narration: “Promotions connect approved partner offers to eligible residents.
  Create the business first, then the ad content, dates, call to action and
  property targeting. Activate only after reviewing the preview and coupon
  terms. Renter impressions, clicks and coupon actions feed the statistics;
  use them to evaluate the campaign without exposing resident identity.”

## 29 — Manager mobile essentials

- Audience: tenant admins and property managers; 4 minutes.
- Capture: mobile sign-in, dashboard, property/renter/lease/payment/ticket tabs,
  notifications, profile, dark mode and language.
- Narration: “The Manager app puts daily operational queues on mobile. The home
  dashboard highlights urgent work, while each tab opens the same tenant-scoped
  property, renter, lease, payment and ticket records used on web. Notifications
  deep-link to the related item. Profile contains language, theme and sign-out.”

## 30 — Manager mobile operations

- Audience: managers; 5 minutes.
- Capture: finance/report views, vendor/staff/settings, meeting and facility
  approvals, listing management, gate-pass queue and report.
- Narration: “Use the Queue and service screens for work that needs a mobile
  response. Review the record before approving, changing status or recording a
  financial action. Manager mobile respects property assignments and feature
  access, so unavailable modules may be intentionally hidden. Complex setup and
  bulk operations remain easier on web.”

## 31 — Renter mobile essentials

- Audience: renters; 4 minutes.
- Capture: home, current lease, Wallet/payment schedule, penalties, tickets,
  notifications, profile, dark mode and language.
- Narration: “The Renter app home summarizes the active lease and upcoming work.
  Wallet contains installments, payment status and receipts. Tickets provide the
  service conversation and closure flow, while penalties explain outstanding
  charges. Notifications take you to the related screen. Use Profile for account,
  language, theme and sign-out.”

## 32 — Renter mobile services

- Audience: renters; 5 minutes.
- Capture: marketplace browse/wishlist, promotions, meetings, facility/parking
  booking, gate-pass creation, QR share and approval status.
- Narration: “Resident services are grouped around the active tenancy. Browse and
  save marketplace listings, open targeted offers, review meetings, and request
  facilities or parking. Gate Pass creates a time-limited visitor credential;
  verify the guest details before sharing the branded QR image and monitor its
  approval and entry status.”

## 33 — Security app

- Audience: security guards; 5 minutes.
- Capture: phone/OTP sign-in, assigned-property selection, expected visitors,
  QR scan and manual code, result verification, admit/deny, walk-in creation,
  exit and recent-status queue.
- Narration: “Sign in with the guard phone number registered by the property
  team and select only an assigned property. Scan the live visitor QR or enter
  its numeric code, then compare the guest, unit, validity and status before any
  action. Record admission, denial and exit accurately. For walk-ins, capture
  identity, unit, purpose and any approved photo according to property policy.”

## Recording acceptance checklist

- The workflow passed the deployed production E2E scenario before recording.
- No real customer name, email, phone, payment credential or document appears.
- The persistent tutorial tenant has only the feature flags required for the
  current chapter, enabled with explicit approval.
- Every saved state and confirmation is visible for at least one second.
- Cursor movement is deliberate; failed attempts, spinners and dead time are cut.
- Narration matches the visible deployed UI and does not promise unshipped work.
- Captions are generated from the final narration and checked for product terms.
- Final MP4 is H.264/AAC, 1080p, with normalized narration and no clipped audio.
