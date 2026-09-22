# RentAxis narrated tutorial storyboards

These storyboards are the recording contract for the tutorial library. Record
at 1920×1080, browser zoom 100%, English UI, and a clean disposable demo tenant.
Use only `Tutorial Demo` names and synthetic contact details. Pause briefly
after every saved state so the viewer can confirm the result.

Audio production: record the silent Playwright/browser capture first, generate
an expressive AI narration track from the reviewed script, then normalize and
mux it with `ffmpeg`. The preferred production provider is Azure
`MAI-Voice-2` with a consistent prebuilt voice; OpenAI `gpt-4o-mini-tts` and
reviewed WAV/MP3 files generated with Gemini TTS are also accepted. Do not add
an automated spoken introduction or disclosure before the reviewed narration.
The renderer uses a deliberate tutorial rate, holds the final
video frame if the voice track runs longer, and keeps narration around -16 LUFS
integrated with UI audio muted. macOS `say` remains a draft-only fallback.
Arabic-localization segments remain narrated in English unless an Arabic voice
track is commissioned separately.

## 01 — Sign in and navigate RentAxis

- Audience: all users; 2–3 minutes.
- Capture: sign in, identify the role-specific sidebar, switch EN → AR → EN,
  open My Profile, update the phone number, log out, and sign in again.
- Narration: “Welcome to RentAxis. In this tutorial, you will sign in, identify
  the navigation for your role, change the interface language, review your
  profile, and sign out safely. Start at the RentAxis sign-in page. Enter the
  email address and password supplied by your administrator, then select Sign
  In. Never use another person’s account, and do not save a password on a shared
  computer. After authentication, RentAxis opens the landing page allowed for
  your role. The menu is permission-aware. A super administrator, tenant
  administrator, property manager, renter, and security guard will not see the
  same options. This is expected and helps keep each person inside their
  authorized workflow. Take a moment to identify the current organization and
  the main navigation. On a smaller screen, open the menu before selecting a
  destination. Now use the language control and switch from English to Arabic.
  Notice that labels change and the page direction moves from left-to-right to
  right-to-left. Open one menu, confirm the Arabic layout, then switch back to
  English so the remaining tutorials match the recording. Next, open the user
  menu and choose My Profile. Review your name, email address, role, and phone
  number. Update the phone number only if it is incorrect, save the change, and
  wait for the confirmation message before leaving the page. Password controls
  are also available here when your account supports password authentication.
  Use a unique password and never share it in a ticket, note, or screen
  recording. Finally, open the user menu and select Logout. Confirm that the
  protected navigation disappears and the sign-in page returns. Sign in once
  more to verify the account still works. You now know the common navigation
  pattern used throughout RentAxis. At the end of every session on a shared
  device, return to this menu and log out.”

## 02 — Dashboard, search, notifications, and help

- Audience: all web users; 2–3 minutes.
- Capture: explain KPIs, open the command palette with Ctrl/Cmd+K, find a lease,
  open notifications, mark one read, open Help Center, and search an article.
- Narration: “This tutorial introduces four tools you will use throughout
  RentAxis: the dashboard, global search, notifications, and the Help Center.
  Begin on the dashboard. The cards at the top summarize the active organization
  and the work visible to your role. Read each label before interpreting a
  number. A collection total, occupancy figure, renewal follow-up, or open ticket
  count represents a different operational question. Filters and the active
  organization determine which records contribute to the result. Use dashboard
  figures as signals, then open the related list or report when you need the
  underlying records. Next, open global search from the header. You can also use
  Control K on Windows or Command K on macOS. Enter part of a known lease,
  renter, cheque, or payment identifier. Search results are role-aware and
  tenant-scoped, so you will only receive destinations you are allowed to open.
  A super administrator can also find organizations. Choose the prepared demo
  lease and confirm that RentAxis opens the correct lease detail instead of a
  general search-results page. Return to the dashboard and open Notifications.
  The unread indicator shows items that have not yet been acknowledged. Select
  one notification, review its title, time, and message, then follow the related
  record when a link is available. Return to the notification list and mark the
  item as read. It should leave the Unread view immediately while remaining
  available in the full history. Use Mark All Read only after reviewing the
  outstanding items; it is not a substitute for completing the work they
  describe. Now open the Help Center. Help content is organized around roles and
  capabilities. Search for Security Guard, open the matching article, and
  compare its responsibilities with the available navigation. Guided tours can
  point to controls inside the running application, while articles explain the
  wider workflow and safety rules. If search returns nothing, check spelling and
  confirm that the feature is enabled for the organization. To finish, return to
  the dashboard and open global search once more with the keyboard shortcut.
  Remember the pattern: use dashboard totals to identify work, search to reach a
  record quickly, notifications to respond to events, and Help whenever you
  need the approved process.”

## 03 — Roles, permissions, and organization switching

- Audience: super admins and organization admins; 2–3 minutes.
- Capture: show Super Admin, Tenant Admin, Property Manager, Tenant User,
  Renter, and Security Guard scopes; switch the active organization; compare
  navigation using prepared accounts.
- Narration: “RentAxis separates access by organization, role, and property
  assignment. This tutorial shows how those boundaries affect the interface and
  why an unavailable menu is often correct behavior rather than an error. Start
  with the prepared Super Admin account. A super administrator can provision
  organizations and move between tenant contexts for authorized support and
  administration. Open the organization switcher and note the currently active
  organization before selecting the Tutorial Demo organization. The page
  reloads inside that tenant context. Switching context does not copy, merge, or
  expose records from another organization. Always verify the organization name
  before creating, editing, or deleting data. Next, sign in with the prepared
  Tenant Admin account. This role manages users, portfolio data, leasing,
  finance, settings, and enabled services for one organization, but it cannot
  provision unrelated tenants or grant itself platform-level authority. Compare
  the sidebar with the Super Admin view. Now use the Property Manager account.
  Property managers operate only the properties assigned to them. Open the
  property list and confirm that an unassigned demo property is absent. Directly
  navigating to an unassigned record must not bypass that restriction. The
  Tenant User role is more limited and should be used for staff who need a
  defined operational surface without tenant-wide administration. Continue with
  the Renter account. The renter sees their tenancy, payments, maintenance,
  meetings, resident services, and marketplace features rather than internal
  administration. Finally, review the Security Guard role. A guard works with
  assigned-property visitor queues, scans, approvals, admissions, and exits.
  They do not receive access to leases, finance, or tenant configuration. Feature
  access also affects navigation. Listings, Meetings, Email Notifications,
  Lease Renewals, and Gate Pass can be enabled per organization, so a role may
  be valid while its related feature remains intentionally hidden. Finish by
  returning to the Super Admin account and switching back to the original
  organization context. The safe operating habit is simple: check the tenant,
  check the role, and check the property assignment before every sensitive
  action. If access appears wrong, do not borrow a broader account. Ask an
  authorized administrator to correct the role or assignment.”

## 04 — Provision organizations and manage feature access

- Audience: super admins; 2–3 minutes.
- Capture: create a synthetic organization, enter address/TRN/phone, save,
  switch into it, explain all five toggles, enable the required demo features.
- Narration: “In this tutorial, a super administrator creates a synthetic
  organization and enables the services required for the tutorial tenant. Sign
  in as Super Admin and open Organizations. Before creating anything, search for
  the planned Tutorial Demo name so you do not create a duplicate. Select Create
  Organization and enter the approved synthetic legal name. Complete the
  address, phone number, email, and tax registration fields with demo data only.
  These values can appear on documents and communications, so production
  customer details must be verified before a real tenant is saved. Review the
  selected status and submit the form once. Wait for the success confirmation
  and open the new organization from the list. Confirm that its identifier,
  legal details, and active state match the form. Next, use the organization
  switcher to enter the new tenant context. Verify the organization name in the
  header before continuing. Return to the Super Admin organization settings and
  open feature access. RentAxis keeps optional capabilities disabled until they
  are deliberately assigned. Listings enables publication and marketplace
  discovery. Meetings enables scheduling between property teams and renters.
  Email Notifications enables supported outbound delivery. Lease Renewals
  enables renewal opportunities, reminders, and renter intent. Gate Pass enables
  resident visitor credentials and security operations. Turn on only the five
  features required for this isolated Tutorial Demo tenant. Save, wait for the
  confirmation, and refresh the feature view. Each selected toggle should remain
  enabled after the reload. Switch into the organization again and verify that
  the corresponding navigation appears for an authorized tenant administrator.
  If a menu is still absent, check both the feature toggle and the signed-in
  user’s role. Do not enable a capability on a real customer tenant simply to
  make a tutorial screen visible. Feature access should match the contracted
  plan and the customer’s operational readiness. To finish, return to the
  organization record and show where legal details can be corrected and where
  an organization can be deactivated. Do not deactivate the tutorial tenant
  while dependent recording fixtures still exist. The organization is now ready
  for users, portfolio data, and the remaining tutorial workflows.”

## 05 — Create users and staff

- Audience: super admins and tenant admins; 3–4 minutes.
- Capture: create a property manager, assign a property, edit the profile,
  demonstrate role restrictions, remove the assignment, then show staff record
  creation and activation status.
- Narration: “This tutorial explains the difference between application users
  and staff records, then creates a property manager with the correct property
  assignment. Begin in the Tutorial Demo organization and verify its name in
  the header. Open Users and search for the synthetic manager email before
  selecting Create User. Duplicate accounts create confusion and should be
  avoided. Enter the manager’s demo name, unique email address, and phone number.
  Choose Property Manager rather than Tenant Admin because this person will
  operate assigned properties without organization-wide control. The safest
  rule is to grant the lowest role that covers the person’s real work. Save the
  user once and wait for the success message. Open the new user detail and review
  the role, tenant, and account status. If onboarding credentials are displayed,
  deliver them through an approved secure channel and never expose them in a
  recording. Now open Property Assignments. Select the prepared Tutorial Demo
  property, add the assignment, and confirm that it appears in the current list.
  Sign in with the manager account in a separate prepared session. The property
  should be visible, while an unassigned property must remain unavailable from
  both navigation and a direct link. Return to the administrator session and
  edit the manager’s phone number or display name. Save and confirm the update.
  Demonstrate the role boundary by opening the role selector: a tenant
  administrator must not be able to create or promote a Super Admin, and cannot
  administer a user belonging to another tenant. Do not weaken the role simply
  to make a test pass. Next, remove the demo property assignment. Confirm the
  removal and refresh the page; the assignment should not return. Add it again
  only if later tutorial workflows require this manager account. Now open Staff.
  A staff record represents a worker associated with property operations and is
  distinct from an account that can sign in. Create a synthetic staff profile,
  enter its job and contact details, associate the appropriate property, and
  save. Review the Active status and show where it can be changed. A person may
  have a staff profile without application access, and an application user does
  not automatically become staff. When responsibilities change, update property
  assignments promptly. When someone leaves, deactivate access instead of
  sharing or recycling the account. Preserve historical staff records required
  for audit, and delete only synthetic test records that are safe to remove.
  Finish by confirming the manager user and staff profile are clearly identified
  as Tutorial Demo data.”

## 06 — Create a project and property portfolio

- Audience: tenant admins and property managers; 2–3 minutes.
- Capture: create a project/property, add bilingual names, type, emirate,
  address, Makani number and fixed expenses; switch card/table views.
- Narration: “This tutorial creates the portfolio structure that later supports
  units, leases, tickets, listings, finance, and resident services. Begin in the
  Tutorial Demo organization and open Properties. Search for the planned project
  and property names before creating new records. Select Create Project and use
  a synthetic bilingual name that is easy to recognize during recording. Add
  the English name first, then the Arabic name if your organization maintains
  both. Save the project and wait for its confirmation. Open the project and
  choose Add Property. Enter the property’s English and Arabic names exactly as
  approved. Choose the correct property type rather than relying on a default,
  because type can affect how the portfolio is presented and filtered. Select
  the emirate, enter the full demo address, and add a valid synthetic Makani
  number in the format expected by the form. Makani and address information help
  operations teams identify the physical site; do not copy a real customer
  location into tutorial data. Continue to the financial fields and enter the
  prepared fixed-expense values. These values contribute to property reporting,
  so use a documented amount and currency rather than an arbitrary production
  figure. Review the form from top to bottom. Confirm the project, bilingual
  names, type, emirate, address, Makani number, and expenses before saving once.
  When the success message appears, open the property detail and compare the
  saved values with the source fixture. Return to the portfolio list. Use Card
  view to scan property names, occupancy, and summary information visually.
  Switch to Table view when you need compact comparison across a larger
  portfolio. Apply a search or filter and confirm that the new Tutorial Demo
  property appears in both views. Open it from the table, then return and open
  it from the card to prove both destinations resolve to the same record. If the
  property will be assigned to a manager, verify that assignment separately
  rather than sharing a tenant-admin account. Finish on the property detail
  page. The portfolio foundation is now ready for buildings, units, contacts,
  amenities, parking, leases, and listings.”

## 07 — Buildings, units, contacts, amenities, and parking

- Audience: tenant admins and property managers; 2–3 minutes.
- Capture: add a building and floor count, create a unit, add an emergency
  contact, create a bookable amenity and parking spot, edit each, then explain
  deactivation versus deletion.
- Narration: “In this tutorial, we complete the operational structure inside a
  property: buildings, units, contacts, amenities, and parking. Open the prepared
  Tutorial Demo property and start with Buildings. Select Add Building, enter a
  clear bilingual name, and provide the correct floor count. Save, reopen the
  building, and confirm the floor information. Next, open Units and create a
  synthetic vacant unit. Select the building and floor, enter the unit number,
  type, bedroom or layout details when available, and the prepared expected-rent
  value. Choose the correct currency and status. Unit status matters throughout
  RentAxis: a vacant unit can be selected for a new lease or listing, while an
  occupied unit should reflect an active tenancy. Save and verify that the unit
  appears under the intended building. Edit one non-critical demo field, save,
  and confirm the change persists. Continue to Contacts. Add an emergency or
  operations contact with a synthetic name, role, phone number, and email. Use
  the contact type that matches its purpose so staff can find it quickly during
  an incident. Do not enter personal details that are not approved for the
  tenant directory. Now open Amenities or Facilities and create a bookable demo
  amenity. Enter English and Arabic names, a useful description, operating
  hours, capacity, and any booking rules exposed by the form. Mark it Active and
  Bookable so the renter request flow can use it later. If fees or time-slot
  settings are present, use the prepared fixture values and explain that they
  determine availability. Save, reopen, and confirm the amenity is visible in
  the property inventory. Move to Parking and create a synthetic parking spot.
  Provide the label, location or level, vehicle restrictions if configured, and
  the current availability state. Mark it bookable or assignable only when
  residents are allowed to request it. Save and verify the result. Demonstrate
  an edit on each inventory type without deleting the record. Deactivation is
  the safer choice for a building contact, amenity, or parking spot that should
  no longer be used but still appears in historical operations. Deletion should
  be reserved for an erroneous synthetic record with no dependencies. Finish by
  returning to the property overview. Confirm that the building, vacant unit,
  contact, amenity, and parking spot all belong to the same Tutorial Demo
  property. This inventory now supports leasing, facility requests, parking
  allocation, listings, and property operations.”

## 08 — Import a property portfolio in bulk

- Audience: tenant admins; 2–3 minutes.
- Capture: download the current template, show required columns, upload a
  synthetic workbook, review validation errors, correct them, and verify the
  resulting property/unit/renter/lease counts.
- Narration: “Bulk portfolio import is intended for controlled onboarding and
  migration. It can create many connected records, so prepare and validate the
  workbook before uploading it. Open Bulk Import from the portfolio area and
  download a fresh template from the running version of RentAxis. Do not reuse
  an old template because required columns and accepted values can change. Open
  the downloaded workbook outside the browser and identify the sheets and
  required headings. The template may include projects, properties, buildings,
  units, renters, leases, and payment schedules. Preserve the column names and
  do not insert decorative rows. Enter only synthetic Tutorial Demo data for
  this walkthrough. Use stable reference values so a unit points to the correct
  building, a lease points to the correct renter and unit, and installment totals
  reconcile with the lease. Save a deliberately invalid copy first. Return to
  RentAxis, choose the file, and upload it for validation. Review the reported
  row number, column, and message. A validation failure should not silently
  create a partial portfolio. Correct every issue in the workbook rather than
  editing around the error in production. Upload the corrected copy and wait for
  validation to finish. Review the preview or import summary before confirming.
  Compare the counts for projects, properties, buildings, units, renters,
  leases, and installments with the prepared source totals. Start the import
  once and keep the page open until RentAxis reports completion. If an import is
  still processing, do not submit the same workbook again. After success, open
  the resulting property and inspect representative records. Confirm one unit’s
  type, rent, and status; one renter’s contact details; one lease’s dates and
  amount; and the complete payment schedule. Add the installments and verify
  they reconcile with the lease total and configured charges. Review the import
  history so the operator, time, source file, and result remain auditable. Bulk
  import is not a substitute for unsupervised recurring edits. For later
  corrections, use the normal record workflows unless an approved migration
  procedure says otherwise. Finish by identifying the imported records as
  Tutorial Demo data and retaining the source workbook securely for
  reconciliation.”

## 09 — Manage renters and portal access

- Audience: tenant admins and property managers; 2–3 minutes.
- Capture: create a renter with bilingual name, email, phone and language,
  enable the portal account, show the generated onboarding credential, edit the
  profile, and explain secure delivery.
- Narration: “This tutorial creates a renter profile and enables resident portal
  access. Open Renters inside the Tutorial Demo organization and search for the
  planned email address first. Select Create Renter and enter a synthetic English
  name, Arabic name, email address, and phone number. Confirm each contact value
  carefully because notifications and account onboarding may use them. Select
  the preferred language so supported communication is presented appropriately.
  Add any required identification or address fields using approved demo data;
  never place a real identity document number in a tutorial tenant. Review the
  form and save once. Open the new renter detail and verify that the bilingual
  name, contacts, language, organization, and active status match the fixture.
  Next, choose the portal-account action. Explain that the renter profile stores
  tenancy and contact information, while the portal account grants the ability
  to sign in. Confirm the account creation. If RentAxis displays a temporary
  onboarding credential, hide it from the recording and deliver a real
  credential only through an approved secure channel. The resident should change
  a temporary password at first sign-in. Use the prepared renter session to sign
  in and confirm that only renter navigation appears. Return to the administrator
  session and edit a safe demo field such as the preferred language or phone
  number. Save, refresh, and verify the update. Show the portal-access status and
  where an authorized administrator can disable the account without erasing the
  renter’s lease history. Do not recycle an old resident account for a new
  person. Finish by confirming the Tutorial Demo renter is active and ready to
  be selected in the lease wizard.”

## 10 — Draft a lease and preview its payment plan

- Audience: tenant admins and property managers; 2–3 minutes.
- Capture: complete all five wizard steps: parties, terms, charges, payment
  plan, and final review; save as draft and open the lease.
- Narration: “This tutorial creates a complete draft lease and reviews its
  payment plan before any unit is occupied. Open Leases and select Create Lease.
  The wizard is divided into five stages, and the review at the end is as
  important as the data entry. In Parties, choose the Tutorial Demo property,
  then select the prepared vacant unit and renter. Confirm the unit number and
  resident rather than choosing the first search result. Continue to Terms.
  Enter the approved start and end dates and verify that the end follows the
  start. Add the monthly or annual rent according to the form, the security
  deposit, payment method, and any Ejari or reference fields available at the
  draft stage. In Charges, review the tax treatment and add only approved
  recurring or one-time charges. Identify each charge clearly so the renter and
  finance team can understand it later. Do not hide a charge inside the base
  rent. Continue to Payment Plan. Choose the required number of installments
  and the distribution strategy. RentAxis generates due dates and amounts from
  the lease terms. Inspect every installment, including the first and final
  amount. Confirm that due dates fall inside the intended period and that the
  total reconciles with rent, tax, and included charges. If the final installment
  carries a rounding difference, verify that the overall total is still correct.
  Use Preview when available to test another installment configuration before
  committing it. Move to Final Review and read the summary from top to bottom:
  organization, property, unit, renter, dates, rent, deposit, taxes, additional
  charges, and payment schedule. Go back to the relevant step if anything is
  wrong. Select Save as Draft once and wait for confirmation. Open the resulting
  lease detail. Its status should be Draft, the selected unit should remain
  eligible until the activation or acceptance workflow, and the payment-plan
  preview should match the wizard. A draft is the safe state for correction; it
  is not proof of a signed agreement. Demonstrate an allowed metadata edit and
  verify the schedule again. Finish on the lease overview, ready for contract
  preview, generation, and renter signature in the next tutorial.”

## 11 — Generate, review, and sign a tenancy contract

- Audience: tenant admins and renters; 2–3 minutes.
- Capture: preview the PDF, generate the contract, download it, sign in as the
  renter, reject with the resulting Draft state, regenerate, verify one current
  PDF, then accept.
- Narration: “This tutorial takes a draft lease through contract preview,
  generation, renter rejection, clean regeneration, and acceptance. Open the
  prepared Tutorial Demo draft lease and review its parties, property, unit,
  dates, rent, deposit, charges, and payment-plan preview first. Contract
  generation should never be used to discover basic lease errors. Select Preview
  Contract. RentAxis renders the agreement without creating the final signed
  artifact. Inspect the tenant and renter names, unit, term, financial amounts,
  installment schedule, and signature areas. Close the preview and correct the
  lease if any value is wrong. When the preview is accurate, choose Generate
  Contract and confirm the action. Wait for the success state. The lease should
  move to Pending Signature and one current PDF should become available. Open or
  download the file through the authenticated control, confirm that it belongs
  to this lease, and avoid exposing its URL or any credential in the recording.
  Now switch to the prepared renter account. Open the lease awaiting signature,
  review the document, and choose Reject. Enter the prepared synthetic reason so
  the administrator understands what must change. Confirm rejection once. The
  renter view should show the updated state, and the administrator view should
  return the lease to Draft. Back in the administrator session, make the safe
  demo correction and preview again. Select Generate Contract a second time.
  Verify that RentAxis presents one current document and does not leave the
  rejected PDF as another active agreement. Switch back to the renter account,
  open the regenerated document, and compare the corrected field. Select Accept
  only after the agreement is correct. Confirm the action and wait for the final
  state. Acceptance activates the lease, marks the unit occupied, and creates or
  confirms the operational payment schedule. Return to the administrator view
  and verify all three results: Active lease status, occupied unit state, and the
  expected installments. The important control points are preview before
  generation, a recorded rejection reason, replacement rather than accumulation
  of rejected artifacts, and acceptance only by the authorized renter.”

## 12 — Activate and administer a lease

- Audience: tenant admins and property managers; 2–3 minutes.
- Capture: activate an eligible lease, edit allowed metadata, add an interaction
  note, show the event history and payment schedule, and explain attachments.
- Narration: “This tutorial covers the day-to-day administration available after
  a lease is eligible for activation. Open the prepared Tutorial Demo lease and
  confirm its renter, unit, dates, rent, deposit, and current state. If the lease
  is still in an eligible pre-active state and the organization’s process allows
  administrator activation, select Activate and review the confirmation. Submit
  once, wait for success, and verify that the lease becomes Active and the unit
  becomes Occupied. Never activate a duplicate or unapproved agreement just to
  advance a screen. Next, open Edit Metadata. Update only fields that remain
  editable after activation, such as the prepared Ejari reference, operational
  notes, or permitted payment details. Save and refresh the page to prove the
  value persists. Financial terms that require a formal amendment should not be
  disguised as a metadata edit. Open the Payment Schedule and compare the number
  of installments, due dates, amounts, methods, and statuses with the activated
  lease. If the workflow permits schedule editing, demonstrate the prepared safe
  change on an unpaid installment, preview the resulting schedule, and confirm
  totals before saving. Do not rewrite a cleared or deposited payment without an
  approved correction process. Now open Interactions or Notes. Add a synthetic
  follow-up entry describing the channel, purpose, and outcome. Save it and show
  that the author and timestamp are recorded. Interaction notes should be factual
  and should not contain passwords, payment credentials, or irrelevant personal
  information. Continue to the lease history or event timeline. Point out the
  creation, contract, activation, metadata, schedule, and interaction events that
  are visible. This shared record helps another operator understand what changed
  without relying on private messages. Finally, open Attachments and explain the
  control without uploading a real document in this rehearsal. Use only files
  that belong to the lease, verify the document type and renter before upload,
  and remove an erroneous synthetic attachment through the approved cleanup
  path. Finish by confirming the lease remains Active, its current metadata is
  correct, and the schedule still reconciles with the agreement.”

## 13 — Renew, extend, terminate, and settle a lease

- Audience: admins, property managers, and renters; 2–3 minutes.
- Capture: open a renewal opportunity, send a reminder, capture renter intent,
  close/mark renewed, extend an active lease, preview settlement, add a deduction
  and credit, save draft, then finalize termination.
- Narration: “This tutorial follows the two possible paths near the end of a
  lease: renewal or move-out settlement. Begin with the prepared Tutorial Demo
  lease inside the renewal notice window. Open the renewal opportunity and
  review the current end date, proposed terms, follow-up status, and renter
  response. Send the prepared reminder once and confirm that its timestamp is
  recorded. Repeated reminders should follow the organization’s communication
  policy rather than being sent simply to clear a dashboard item. Switch to the
  renter account and open the renewal banner. Record the prepared intent to
  renew or not renew, submit it, and verify that the response is reflected in
  the administrator view. When the renewal is agreed and the replacement lease
  or approved terms exist, close the opportunity using the correct renewed
  outcome. The follow-up should leave the open queue but remain in history. For
  a simple approved extension, open the active lease, select Extend, enter the
  new end date, and review any resulting schedule impact before confirmation.
  An extension changes the active term; it is not the same as silently editing a
  closed lease. Now demonstrate the move-out path on the separate settlement
  fixture. Open Settlement Preview and verify unpaid rent, open penalties,
  security deposit, existing charges, and the suggested refund or balance. Add
  the prepared deduction with a clear category, description, and amount. Attach
  evidence only when the exact tenant-owned artifact cleanup path is available.
  Add the prepared credit or settlement addition and explain why it increases
  the amount returned or reduces the amount due. Review the arithmetic after
  every change. Save as Draft so another authorized operator can review it. The
  lease must remain unfinalized at this point. Reopen the draft, compare all
  figures with the approved move-out calculation, and verify that the deposit is
  preserved correctly. When approval is complete, select Finalize Settlement
  and accept the irreversible-state warning. Confirm that the settlement is
  finalized, the lease is Terminated, and the unit state is updated according to
  the move-out workflow. Do not finalize while deductions, credits, receipts, or
  approvals are unresolved. Renewal, extension, and termination are distinct
  audited outcomes; always choose the one that reflects the real agreement.”

## 14 — Penalties and cheque-failure fines

- Audience: finance operators and renters; 2–3 minutes.
- Capture: configure fine settings, fail a cheque for each supported reason,
  show the generated penalty, renter visibility, payment state, receipt, and an
  authorized waiver.
- Narration: “This tutorial configures cheque-failure fines and follows a
  penalty from creation to renter visibility, payment, receipt, and waiver. Open
  Fine Settings in the Tutorial Demo organization. Review each supported failure
  reason, including insufficient funds or bounced cheque, signature mismatch,
  closed account, and any late-day rule exposed by the current configuration.
  Enter only the approved synthetic amounts or percentages and save once. Reload
  the settings to confirm they persist. Fine rules should be approved before a
  cheque is failed; changing a rule later must not be assumed to rewrite the
  meaning of historical penalties. Open the prepared deposited cheque and
  choose Mark Failed. Select the actual prepared reason rather than the first
  option, add a clear operational note, and confirm. Verify that the cheque moves
  to its failed state and that RentAxis creates the expected penalty with the
  correct amount, reason, lease, and renter. Repeat only on separate fixtures
  when demonstrating another supported failure reason. Switch to the renter
  account and open Penalties. The new charge should appear in the Open view with
  its reason, amount, date, and related lease information. Return to the finance
  operator and record payment on one demo penalty. Enter the prepared method,
  reference, date, and amount, then save. Open the resulting receipt and confirm
  it identifies the same penalty without exposing payment credentials. Back in
  the renter view, the item should move from Open to the paid or cleared history.
  For a separate approved fixture, demonstrate Waive. Enter an authorization
  note that explains who approved the exception and why, then confirm once. A
  waiver is not a deletion: the original penalty, reason, operator, time, and
  waiver note should remain auditable. Verify the renter sees the waived state
  rather than an outstanding balance. Finish by returning to Fine Settings and
  reminding viewers that the failure reason drives the rule. Accurate settings,
  reason selection, receipts, and waiver notes protect both the renter and the
  property team during reconciliation.”

## 15 — Manage rent cheques end to end

- Audience: tenant admins and property managers; 2–3 minutes.
- Capture: inspect pending installments, attach cheque details/image, collect,
  deposit, clear, download receipt, demonstrate failure, notes, and bulk attach.
- Narration: “This tutorial follows rent cheques from an unpaid installment to
  collection, deposit, clearing, receipt, or failure. Open Cheque Operations and
  filter to the Tutorial Demo property and lease. Begin with a pending
  installment. Compare its amount and due date with the lease payment schedule.
  Choose Attach Cheque Details and enter the prepared cheque number, bank, payer,
  date, and amount. If an approved synthetic image is used, verify that it shows
  no real account information before upload. Cheque extraction can propose
  values from an image, but the operator must compare every field with the
  physical instrument; extraction is assistance, not approval. Save and confirm
  the installment is ready for collection. Select Collect and record the
  prepared collection date and note. The state should advance without changing
  the agreed amount. Next, choose Deposit, enter the deposit date and reference,
  and confirm. Deposit represents handoff to the bank, not settlement. On the
  successful fixture, choose Clear only after the bank result is available.
  Verify the cleared date, payment history, dashboard totals, and downloadable
  receipt. Open the receipt and confirm the renter, lease, installment, and
  amount. Use a separate deposited cheque for the failure path. Select Mark
  Failed, choose the real prepared failure reason, add the bank reference or
  note, and submit. Confirm the failed state and inspect any penalty generated by
  the organization’s fine settings. Add an operational note to explain a safe
  follow-up without altering history. Finally, open Bulk Attach for a set of
  pending installments. Download or review the expected input format, upload an
  approved synthetic file, and inspect the validation preview. Confirm the batch
  only when cheque numbers, installments, and amounts match. Reconcile the
  success, skipped, and failed counts with the source before leaving the page.
  Never resubmit the whole batch to correct one row without checking whether the
  successful rows already changed state. Finish by filtering the cheque list and
  showing the distinct pending, collected, deposited, cleared, and failed
  fixtures. Every transition should have an operator, timestamp, and supporting
  reference so finance can reconstruct the lifecycle.”

## 16 — Configure online rent collection

- Audience: super admins and tenant admins; 2–3 minutes.
- Capture: open gateway settings, select a provider/test mode, enter synthetic
  keys, save, demonstrate masking and renter readiness, then deactivate.
- Narration: “This tutorial configures online rent collection without making a
  real payment. Begin as an authorized super administrator or tenant
  administrator in the Tutorial Demo organization. Open Settings, Rent, and then
  Payment Gateway. Review the current status before making a change. Select the
  prepared provider and keep Test Mode enabled. Test mode is the correct place
  to verify credentials, readiness, and renter presentation before any live
  charge is possible. Enter only synthetic or provider-issued test keys. Never
  paste a live secret into a tutorial recording, ticket, chat, or source file.
  Save once and wait for confirmation. Reopen the configuration and show that
  secrets are masked or represented by a retained-secret indicator rather than
  displayed back in full. This protects the credential but does not prove it is
  valid, so use the supported test or readiness result when available. Switch to
  the prepared renter account and open Payments. Confirm that the online-payment
  control appears only when the tenant has an active eligible gateway and the
  installment can be paid online. Stop before launching or completing any real
  provider charge. Return to the administrator session and explain the move to
  live mode: obtain approved production credentials, confirm callback and
  settlement configuration, complete provider testing, and receive operational
  approval before disabling Test Mode. Rotating a secret should replace it
  without revealing the previous value. To finish the tutorial safely, select
  Deactivate on the synthetic configuration and confirm. Refresh the settings
  and the renter payment view. The configuration should be inactive and online
  readiness should no longer be advertised. If a gateway is unavailable or a
  credential may be compromised, deactivate first and investigate rather than
  asking renters to retry repeatedly.”

## 17 — Chart of accounts, the account template, and charge types

- Audience: finance admins; 2–3 minutes.
- Capture: browse the seeded chart of accounts in tree and flat view, add a
  child account, open the property account template and its per-role name
  patterns, edit one and save, set a tenant-wide default account, open a draft
  lease's charge-line picker to show the charge-type catalogue those roles
  belong to, then set the fiscal year and a lock date.
- Narration: “This tutorial explains where every AED in RentAxis is meant to
  live before it explains how it gets there. Open Finance and choose Chart of
  Accounts. Every account belongs to one of five types — asset, liability,
  income, expense or equity — and the tree view nests each leaf account under
  its group. Select Add Account and create a child under the correct group;
  its type, group and parent cannot change afterward, so choosing them
  carefully matters more than the name. Switch to the flat view and see the
  same accounts organised by type instead of hierarchy, useful when you are
  hunting for one account rather than browsing the structure. Now open
  Settings and choose Account Template. Every property posts to its own
  accounts, not a shared tenant-wide one, and this page is the pattern
  RentAxis follows when it generates them: one row per role, such as Rent
  Receivable, Advance Rent, Security Deposit or Admin Fee, each with a name
  pattern, the parent group its generated account files under, and whether it
  is enabled. Edit a name pattern and save; the change only affects accounts
  generated after today, not ones a property already has. Below it, Default
  Accounts holds the tenant-wide fallback used only when a property has no
  account of its own for a role — pick one and save. These roles are not
  arbitrary: every charge line on a lease is drawn from the tenant's
  charge-type catalogue — rent, security deposit, admin fee, parking, cooling
  charges, maintenance — and each charge type carries the role it posts to,
  which is why this template reads like a list of charges. Open a draft
  lease's charge-line picker to see that catalogue in use. Finally open
  Settings and choose Fiscal to set the fiscal year's start month and the date
  the books themselves begin. A lock date closes every period through that day
  to new and reversing entries, and it can only move forward, never back, so
  confirm the date before you set one.”

## 18 — Journal vouchers: create, read, and reverse

- Audience: finance admins; 2–3 minutes.
- Capture: open the journal list and filter it, post a manual two-line journal
  voucher, open its detail to read the balanced lines, then reverse it and
  follow the link back to the reversal.
- Narration: “This tutorial covers the ledger's own manual entry point: the
  journal voucher, for whatever a lease, cheque, recognition run, voucher or
  settlement does not already write on its own. Open Finance and choose
  Journals. Every posted entry lands here, tagged with its document type — a
  tenancy contract, a post-dated cheque, a cash and cheque collection, an
  advance rent adjustment, a manual journal voucher and more — and you can
  filter by document type, date range and property. Select New Journal. Enter
  the entry date, an optional property, and a narration that explains the
  entry to whoever reads it later. A journal voucher is a grid of lines, and
  each line picks one account and either a debit or a credit, never both. Add
  a second line, choose its account, and enter the matching amount on the
  opposite side. RentAxis will not let you post until the debits and the
  credits add up to the same total; that balance is not a suggestion, it is
  the definition of a journal. Post it and land on its detail page. Read every
  line: the account, the debit or the credit, its own narration, and the unit
  or tenant it is tied to when one applies. The header repeats the totals so
  you can confirm the balance without adding the lines yourself. Only a
  manual journal voucher that has not already been reversed can be reversed
  from this screen; an entry that belongs to a lease, a cheque, a recognition
  period, a voucher or a settlement is corrected from its own screen instead,
  because reversing it here would leave that document posted while its ledger
  vanished. Select Reverse, confirm the date and give a reason, then confirm
  again. RentAxis writes a mirror entry with every debit and credit swapped
  and links the two permanently: open either one and follow Reversal of or
  Reversed by to reach the other. The original is not deleted and cannot be
  edited; the ledger keeps both, and together they net to zero.”

## 19 — Vendors and bank accounts

- Audience: tenant admins; 2–3 minutes.
- Capture: create/edit a vendor, open details, add a property bank account,
  choose the default, update branch information, and explain deactivation.
- Narration: “This tutorial creates a vendor and a property bank account, then
  explains default selection and deactivation. Open Vendors in the Tutorial Demo
  organization and search for the planned synthetic company first. Select Add
  Vendor and enter its legal name, service category, contact person, phone,
  email, address, and prepared tax details. Use demo values only. Save and open
  the vendor detail. Verify the active status and contact information, then edit
  one safe field and confirm that the update persists. Vendor details should be
  accurate because they are used for operational assignment and financial
  review. Next, open Bank Accounts. Select Add Account and choose the Tutorial
  Demo property that will receive the funds. Enter the synthetic bank name,
  account label, masked demo account value or IBAN, currency, branch, and other
  required information. Never display a real banking credential in a recording.
  Choose Default only when this account is the approved primary destination for
  the relevant property and workflow. Save and reopen the record. Confirm the
  property, currency, masked identifier, active status, and default indicator.
  Create or use a second synthetic account to demonstrate changing the default.
  The interface should leave only one appropriate default rather than two
  competing destinations. Update the branch information on the demo account,
  save, and verify it. Finally, show Deactivate. Before deactivation, check for
  active workflows or references that still require the vendor or bank account.
  Deactivation preserves history while preventing new use; deletion is not a
  substitute for financial record retention. Finish on the vendor detail and
  bank-account list so viewers can see where both operational payees and property
  receiving accounts are maintained.”

## 20 — Dashboards and financial reports

- Audience: super admins and tenant admins; 2–3 minutes.
- Capture: explain collections and occupancy KPIs, open income statement,
  balance sheet and NOI views, change period/property filters, inspect details.
- Narration: “This tutorial moves from dashboard indicators to detailed
  financial reports. Start on the Tutorial Demo dashboard and identify the
  selected organization, property scope, and reporting period. Review collection
  totals, outstanding amounts, occupancy, income, expenses, and any renewal or
  operational indicators visible to the current role. A dashboard card is a
  signal, not the complete accounting explanation. Open Financial Reports and
  choose Income Statement. Apply the prepared date range and property filter.
  Review income, direct and indirect expenses, and net profit. Open the available
  detail rows and connect an unusual total to its underlying transactions. Next,
  open Balance Sheet using the same date and property scope. Identify assets,
  liabilities, and equity and avoid comparing it with an income statement that
  uses a different filter. Continue to the net operating income or NOI report.
  Review property income and operating expenses, then confirm the displayed net
  result. If Trial Balance, aging, VAT, vendor ledger, or other report types are
  available for the role, open the prepared example and show how its rows relate
  to accounts, renters, vendors, or transactions. Change the property filter and
  point out that report totals update to the selected scope. Restore the Tutorial
  Demo property before concluding. When a number looks wrong, drill down, check
  the period, verify account mappings, and reconcile with payment schedules and
  source transactions before assuming the dashboard is incorrect. Export or
  share a report only through an approved customer channel, and inspect the file
  for sensitive information first. Finish by returning to the dashboard with the
  same filters. Consistent organization, property, and period selection is what
  makes comparisons meaningful.”

## 21 — Renter payments

- Audience: renters; 2–3 minutes.
- Capture: sign in as a renter, open schedule/history, inspect status and due
  dates, open a receipt, and show online payment when a gateway is active.
- Narration: “This tutorial shows renters how to understand the payment schedule,
  history, receipts, and online-payment readiness. Sign in with the prepared
  Tutorial Demo renter account and open Payments or Wallet. Begin with the active
  lease summary and confirm the property and unit. Review the total schedule and
  then read each installment’s amount, due date, payment method, and status. A
  pending installment is not the same as a collected, deposited, cleared,
  failed, or overdue item. Open the prepared cleared payment and review its
  details. Select the receipt and confirm that it identifies the organization,
  renter, lease, amount, date, and reference. Download or share a receipt only
  through an approved channel because it can contain personal and financial
  information. Return to the schedule and open the prepared failed or overdue
  item. Follow the related penalty when one exists, but do not attempt to change
  payment status from the renter account. If an amount, due date, or state looks
  wrong, contact the property team with the installment reference rather than
  submitting a duplicate payment. When an active gateway is configured and the
  installment is eligible, RentAxis displays an online-payment action. Open the
  readiness view but stop before a real charge in this tutorial. During a live
  payment, complete the external provider flow once and wait for RentAxis to
  receive confirmation. Repeated clicks can create confusion while a provider
  callback is still processing. Finish by switching between schedule and payment
  history. The schedule explains what is due, while history and receipts prove
  what has completed.”

## 22 — Submit and manage maintenance tickets

- Audience: renters and operations teams; 2–3 minutes.
- Capture: renter creates ticket; manager assigns vendor/staff, adds estimate and
  reply, changes status, renter confirms OTP closure and rating; open history and
  reports.
- Narration: “This tutorial follows a maintenance ticket from renter submission
  through assignment, work updates, closure confirmation, rating, history, and
  reporting. Start in the prepared renter account and open Maintenance or
  Tickets. Select Create Request. Enter a concise synthetic title and describe
  the issue, exact location, access considerations, and urgency. Choose the
  correct property and unit from the active tenancy and select a priority that
  reflects actual impact. Attach only an approved synthetic image that helps the
  team diagnose the issue; never include unrelated people, documents, or private
  areas. Submit once and open the new ticket detail. Confirm its reference,
  status, conversation, and timeline. Switch to the manager account and open the
  same ticket. Review the renter’s description before assigning work. Choose the
  prepared vendor or staff member, add the estimated cost and expected arrival
  time when available, then save. The controls should remain disabled while the
  request is processing so a transition cannot be submitted twice. Add a reply
  explaining the next step. Change the status to In Progress only when work has
  actually started. Record a second update and mark the work Resolved when the
  technician has finished, not merely when it has been assigned. Return to the
  renter account. Review the resolution and conversation. If renter closure OTP
  is enabled, use the prepared test code to confirm that the work is complete.
  An invalid or expired code must not close the request. After confirmation,
  submit the prepared rating and short feedback. Verify that the ticket reaches
  its final closed state. Return to the manager view and open History. Confirm
  that creation, assignment, estimates, ETA, replies, status transitions, OTP
  confirmation, and rating appear with timestamps and actors. Finally, open
  Ticket Reports and locate the fixture by property, status, or period. Reports
  should reflect the final state without erasing the conversation. A reliable
  maintenance record tells the full story: what the renter reported, who owned
  the work, when status changed, what it cost, and whether the renter confirmed
  the outcome.”

## 23 — Schedule and manage meetings

- Audience: admins, property managers, and renters; 2–3 minutes.
- Capture: create a meeting with property/unit context, participant and time;
  show renter view, update status/details, then cancel/complete.
- Narration: “This tutorial schedules a meeting and keeps its status visible to
  both the property team and renter. Sign in with the prepared renter or manager
  account, open Meetings, and select Create Meeting. Choose the Tutorial Demo
  property and unit, select the intended participant, and enter a clear purpose.
  Set the prepared date, start time, and any duration or location available in
  the form. Review the time zone and avoid placing private discussion details in
  the title. Submit once and open the meeting detail. Confirm the organizer,
  participant, property, unit, purpose, time, and initial status. Switch to the
  other participant’s account and open Meetings. The same appointment should be
  visible with the information appropriate to that role. Return to the manager
  session and approve or confirm the prepared meeting when that workflow is
  required. Open the calendar or filtered list and verify the appointment is in
  the expected slot. Demonstrate an allowed update to the time or note and wait
  for confirmation. The renter view should reflect the update rather than a
  duplicate meeting. When plans change, choose Cancelled and provide a useful
  reason instead of silently deleting the record. On a separate completed
  fixture, choose Complete after the meeting has occurred. Confirm that it
  leaves the active queue while remaining in history. Accurate status keeps
  dashboards and notifications reliable and gives both parties one shared
  record of the appointment.”

## 24 — Facilities and booking approvals

- Audience: renters and property managers; 2–3 minutes.
- Capture: create a bookable amenity and parking spot, renter requests each,
  manager approves with note, renter cancels one, release the completed booking.
- Narration: “This tutorial connects facility and parking inventory with renter
  requests and manager approvals. Begin as the property manager and open the
  Tutorial Demo property’s Facilities. Confirm that the prepared amenity is
  Active and Bookable, with the expected hours, capacity, and rules. Review the
  prepared parking spot and verify that its current state allows a request.
  Switch to the renter account and open Facilities. Choose the amenity, review
  its availability and instructions, select the prepared date and time, and
  submit the booking request once. Open My Requests and confirm the Pending
  status. Create the prepared parking request as a separate item and verify its
  unit and requested spot. Return to the manager account and open Booking
  Approvals. Filter to Pending and open the amenity request. Review the renter,
  lease or unit, requested period, capacity, and conflicts. Add the prepared
  approval note and approve. Repeat for the parking request only after checking
  that the spot is still available. Switch back to the renter and refresh My
  Requests. Both approved states and the manager note should be visible. On the
  future amenity fixture, choose Cancel and confirm. Cancellation should free
  the reserved period without deleting the audit history. On the completed or
  allocated parking fixture, use Release when the allocation is no longer
  needed. Return to the manager inventory and verify that availability has been
  restored. Managers should reject conflicts or policy violations with a useful
  note rather than approving and correcting later. The complete workflow links
  active inventory, renter request, review, approval, cancellation or release,
  and restored availability.”

## 25 — Gate passes and walk-in visitors

- Audience: renters, managers, and security guards; 2–3 minutes.
- Capture: create resident guest pass, manager approval, guard assigned-property
  view, QR/code scan, admit and exit, walk-in registration, policy and report.
- Narration: “This tutorial follows resident guest passes and walk-in visitors
  across the renter, manager, and security applications. Start in the prepared
  renter account and open Gate Pass. Select Create Pass and enter the synthetic
  guest name, phone, visit date or window, unit, purpose, vehicle details, and
  pass type. Review recurring dates carefully when creating a recurring pass.
  Submit once and open the detail. The request should show Pending until manager
  approval is required. Switch to the manager account and open Gate Pass
  Approvals. Find the fixture, compare the guest, renter, unit, property, visit
  window, vehicle, and purpose, then approve. The item should leave the pending
  queue and become Active. Open the renter pass again and show its branded QR,
  numeric code, validity, and approval status. Share a pass only with the
  intended visitor. Now use the Security app with the assigned Tutorial Demo
  property. Review Expected Visitors, select Scan, and scan the live QR or enter
  the numeric code. Before taking action, compare the displayed guest, unit,
  validity, vehicle, and current status with the person at the gate. An expired,
  denied, wrong-property, or already-used result must not be treated as allowed.
  Admit the valid fixture once and confirm the entry status. Record Exit when the
  visitor leaves so recent activity remains accurate. Next, demonstrate a
  walk-in. In Security, select Register Walk-In and enter the synthetic identity,
  destination unit, purpose, phone, and vehicle information. Capture a photo only
  when property policy requires it and exact tenant-owned cleanup is available.
  Submit the request, switch to the renter approvals view, and approve the
  visitor. Return to Security, refresh the walk-in status, verify the live
  approved result, and admit. Open gate policy, assigned guards or vendors, and
  the access report to show where administrators control and review the process.
  Never admit from a screenshot or verbal claim alone. The guard must validate
  the current RentAxis result and record entry and exit accurately.”

## 26 — Publish and manage property listings

- Audience: tenant admins and property managers; 2–3 minutes.
- Capture: create a listing from a vacant unit, add details and media, preview,
  publish, inspect interests, unpublish, edit, and explain delete.
- Narration: “This tutorial creates, publishes, reviews, and withdraws a property
  listing. Open Listings in the Tutorial Demo organization and select Create
  Listing. Choose the prepared vacant property and unit. An occupied or
  unavailable unit should not be marketed as vacant. Enter the listing title,
  English and Arabic descriptions when supported, price, availability date,
  property details, amenities, contact method, and any viewing instructions.
  Use approved synthetic values and verify that public copy contains no internal
  notes. Continue to Media and add only approved Tutorial Demo images through
  the exact tenant-owned artifact path. Arrange the cover image and confirm that
  every photo belongs to the property. Open Preview and inspect the card and full
  detail as a public visitor would see them. Check the property name, unit facts,
  price, availability, amenities, description, contact action, and media. Return
  to the editor to correct any mismatch. Select Publish once and wait for the
  Active or Published state. Open the public marketplace in a separate session
  and verify the listing appears and its detail loads without administrator
  access. Use the prepared renter account to express interest, then return to the
  manager listing workspace and open Interests. Review the renter, time, note,
  and current status without exposing unrelated resident data. When the unit is
  temporarily unavailable, choose Unpublish. Confirm that it leaves the public
  catalogue while remaining editable and retaining its interest history. Make
  the prepared edit, preview again, and explain that republishing requires the
  same accuracy check. Delete only an erroneous synthetic listing that should
  not remain in history and has no required audit value. Finish with the listing
  safely unpublished until the production recording fixture is approved.”

## 27 — Browse the marketplace and manage a wishlist

- Audience: renters and public visitors; 2–3 minutes.
- Capture: browse tenant marketplace, filter, open details, add/remove wishlist,
  express interest with note, and show the manager-side interest.
- Narration: “This tutorial uses the public and renter marketplace surfaces.
  Begin on the Tutorial Demo marketplace without signing in. Browse the visible
  listings and apply the prepared location, price, property, bedroom, or amenity
  filters. Confirm that the result count and cards update to the selected scope.
  Open the prepared listing and review its images, price, availability, property
  and unit details, amenities, description, and contact action. Public visitors
  can discover approved listing information but cannot access tenant
  administration. Now sign in with the prepared renter account and return to the
  same listing. Select the wishlist or saved-property control. Open Wishlist and
  confirm the listing appears. Remove it once and verify the list updates, then
  add it again for the next step. A wishlist is a private shortlist; it does not
  reserve the unit. Return to the listing and choose Express Interest. Enter a
  concise synthetic note requesting contact and submit once. Wait for the success
  state rather than clicking repeatedly. Switch to the manager session and open
  the listing’s Interests view. The renter’s request and note should appear for
  authorized follow-up. Return to the renter and withdraw the interest when that
  action is available. Removing a wishlist item alone does not cancel an already
  submitted interest, so treat saving and contacting as separate actions.
  Finish by clearing the filters and confirming the catalogue returns to its
  broader view.”

## 28 — Promotions, offers, and coupons

- Audience: tenant admins and renters; 2–3 minutes.
- Capture: create a business and ad, add copy/image/CTA/coupon, target property,
  activate, show renter feed/detail, record click/coupon, inspect statistics.
- Narration: “This tutorial creates a partner business, publishes a targeted
  promotion, exercises its renter actions, and reviews campaign statistics. Open
  Promotions as a tenant administrator and begin with Businesses. Search for the
  Tutorial Demo partner before selecting Add Business. Enter its synthetic
  English and Arabic names, category, contact details, and active status, then
  save. Open Ads or Promotions and create a campaign for that business. Add a
  concise title, subtitle, description, approved image, start and end dates, and
  the intended call to action. Configure the prepared website, phone, or coupon
  action using demo values only. When the offer uses a coupon, enter the code,
  terms, expiry, and redemption instructions clearly. Select the eligible
  Tutorial Demo property or audience target so the offer is not shown to the
  wrong residents. Preview the English and Arabic presentation and verify the
  image, copy, dates, business, action label, and coupon terms. Save as inactive
  first, then choose Activate after review. Switch to the prepared renter mobile
  session and open Offers. The promotion should appear in the eligible feed.
  Open its detail, use the prepared call to action, and reveal or record the
  coupon action once. Do not expose resident identity in the promotion itself.
  Return to the administrator view and open Statistics. Review impressions,
  detail views, clicks, and coupon actions for the campaign. Counts may update
  asynchronously, so refresh once rather than generating fake activity. Use
  aggregate engagement to evaluate the campaign without identifying individual
  residents. Finish by deactivating the Tutorial Demo promotion and confirming
  it leaves the renter feed while its historical statistics remain available.”

## 29 — Manager mobile essentials

- Audience: tenant admins and property managers; 2–3 minutes.
- Capture: mobile sign-in, dashboard, property/renter/lease/payment/ticket tabs,
  notifications, profile, dark mode and language.
- Narration: “This tutorial introduces the daily Manager mobile experience.
  Open the Manager app and sign in with the prepared Tutorial Demo property
  manager account. Confirm the active organization and allow the home dashboard
  to finish loading before interpreting its cards. Review the urgent queues and
  summary totals visible for the manager’s assigned properties. Open Properties
  and select the prepared property. Check its units, occupancy, contacts, and
  operational summary. Return and open Renters, then select the prepared renter
  to review authorized contact and tenancy context. Open Leases and choose the
  active fixture. Review dates, rent, deposit, schedule, penalties, and settlement
  destinations without changing financial state. Continue to Payments or Cheque
  Operations and compare the prepared pending, deposited, cleared, and failed
  items. Open Tickets, select the prepared request, and review its description,
  assignment, conversation, ETA, and history. Status actions should remain
  disabled while a request is in flight. Open Notifications and follow one item
  to its related record, then mark it read and confirm the unread list updates.
  Finally, open Profile. Switch to dark mode and review one operational screen,
  then switch back. Change from English to Arabic, confirm right-to-left layout,
  and restore English for the remaining recordings. Profile also contains
  account details and sign-out. Manager mobile uses the same tenant and property
  boundaries as web; missing records must not be bypassed with another user’s
  credentials. Sign out on any shared device when the walkthrough is complete.”

## 30 — Manager mobile operations

- Audience: managers; 2–3 minutes.
- Capture: finance/report views, vendor/staff/settings, meeting and facility
  approvals, listing management, gate-pass queue and report.
- Narration: “This tutorial covers Manager mobile operations beyond the daily
  essentials. Sign in to the Tutorial Demo manager account and open Finance.
  Review the prepared income and expense transactions, then open Reports and
  apply a property and period filter. Check the profit and loss, NOI, balance,
  trial-balance, or available detail without editing source transactions. Open
  Vendors and select the synthetic vendor, then review Bank Accounts with masked
  identifiers. Continue to Staff and confirm that only authorized property staff
  appear. Open Settings and identify rent configuration, gateway readiness, and
  account mappings; complex credential entry and bulk configuration are safer on
  web. Next, open Meetings. Review the pending fixture, approve it when the
  prepared state requires approval, and open the calendar before completing it.
  Open Facilities and Booking Approvals. Compare the renter, requested time,
  inventory, conflicts, and note before approving or rejecting. Continue to
  Listings, open the prepared listing detail and interests, and avoid publishing
  media that has not passed review. Open the Gate Pass queue. Review the visitor,
  renter, unit, validity, vehicle, and purpose before approving the disposable
  fixture. Then inspect Guard assignments, access policy, managed vendors, and
  the gate report. Every mobile action changes the same production record used
  by web, so wait for confirmation and never tap twice because a response is
  slow. The app respects tenant feature flags and property assignments; an absent
  service can be intentional. Use mobile for timely review and status work, and
  use web for large imports, detailed setup, and other complex administration.”

## 31 — Renter mobile essentials

- Audience: renters; 2–3 minutes.
- Capture: home, current lease, Wallet/payment schedule, penalties, tickets,
  notifications, profile, dark mode and language.
- Narration: “This tutorial introduces the Renter mobile essentials. Open the
  Renter app and sign in with the prepared Tutorial Demo resident account. The
  Home screen summarizes the active tenancy, next payment, and available quick
  actions. Confirm the property and unit before following any action. Open the
  current lease and review its dates, rent, deposit, and status. Continue to
  Wallet or Payments. Read the installment schedule, due dates, methods, and
  statuses, then open the cleared fixture and its receipt. A renter can review
  these records but should contact the property team if an amount appears wrong.
  Open Penalties and compare the prepared open, paid, or waived states. Select a
  penalty to understand its reason and related lease. Next, open Maintenance or
  Tickets. Create is covered in another chapter; here, open the prepared ticket
  and review its conversation, assignment, status, ETA, and history. Open
  Notifications and follow one item to its destination. Mark it read and confirm
  it leaves the unread view. Finally, open Profile and review the account details.
  Switch to dark mode, return to Home, then restore the preferred theme. Change
  the language to Arabic and confirm the right-to-left presentation before
  returning to English. Use only your own renter account, never share a receipt
  or visitor credential publicly, and sign out from Profile when using a shared
  device.”

## 32 — Renter mobile services

- Audience: renters; 2–3 minutes.
- Capture: marketplace browse/wishlist, promotions, meetings, facility/parking
  booking, gate-pass creation, QR share and approval status.
- Narration: “This tutorial covers the renter services available from the mobile
  app. Begin with Browse and apply the prepared marketplace filter. Open the
  Tutorial Demo listing, review its images and details, add it to Wishlist, and
  submit the prepared interest note once. Continue to Offers and open the active
  targeted promotion. Review its business, dates, terms, call to action, and
  coupon before using the demo action. Open Meetings to review the prepared
  appointment, then create a synthetic request with the correct property, unit,
  participant, purpose, and time. Next, open Facilities. Choose the bookable
  amenity, select an available period, and submit the request. Open My Requests
  to see its pending or approved state. Create the prepared parking request and
  explain that cancellation or release restores availability without erasing
  history. Finally, open Gate Pass and select Create. Enter the synthetic guest,
  phone, visit window, purpose, vehicle, and intended unit. Review everything
  before submission. After manager approval, open the pass detail and show the
  branded QR, numeric code, validity, and status. Share the credential only with
  the intended visitor and never post it publicly. Refresh the status after the
  guard scans, admits, or records exit. Open Resident Approvals to review a
  prepared walk-in request and approve only after confirming the identity,
  destination unit, and purpose. These services all depend on the active tenancy,
  tenant feature access, and property policy, so an unavailable action may be
  intentional rather than an app error.”

## 33 — Security app

- Audience: security guards; 2–3 minutes.
- Capture: phone/OTP sign-in, assigned-property selection, expected visitors,
  QR scan and manual code, result verification, admit/deny, walk-in creation,
  exit and recent-status queue.
- Narration: “This tutorial covers the complete Security app workflow. Open the
  app and enter the guard phone number registered by the property team. Request
  the one-time code, enter the current Firebase SMS code, and wait for the guard
  session to load. Never ask another guard to share an OTP. On the home board,
  select only a property assigned to the signed-in guard. Review Expected
  Visitors and confirm the list belongs to that property and current time
  window. Open Scan. Use the camera on a physical device to scan the visitor’s
  live QR, or choose manual entry and type the eight-digit code. The result is
  the decision surface: compare guest name, phone when shown, property, unit,
  vehicle, purpose, validity, pass type, and current state with the person at the
  gate. An Allowed result still requires identity and property-policy checks. An
  expired, denied, wrong-property, inactive, or already-used result must not be
  overridden informally. Select Admit once for the valid disposable fixture and
  wait for the Admitted state. Record Deny with the correct reason when access
  is refused, and record Exit when an admitted visitor leaves. Next, choose
  Register Walk-In. Enter the synthetic visitor identity, destination unit,
  purpose, phone, and vehicle. Capture a photo only when policy requires it and
  the approved tenant-owned artifact cleanup is deployed. Submit and monitor the
  status while the resident reviews the request. When approval arrives, reopen
  the live walk-in detail, verify the same visitor and unit, and admit once.
  Finish on the recent-status queue and confirm the entry, denial, admission, or
  exit appears with the correct time. Security decisions must always use the live
  result, not a screenshot, forwarded code, verbal claim, or cached page.”

## 34 — Post a tenancy contract

- Audience: finance admins and property managers; 2–3 minutes.
- Capture: open the prepared draft contract, review its charge lines and cheque
  grid, post it, then show the two journal types the post produced and the
  recognition schedule it planned.
- Narration: “In this tutorial you will turn a draft tenancy contract into
  accounting entries. Open Leases and select the prepared draft. A contract in
  RentAxis has two grids. The first is its charge lines. Each line names what is
  being charged, the account the income or liability is credited to, the gross
  amount, any discount, and the net. Rent, security deposit and administration
  fee are separate lines because they behave differently: rent is recognised
  across the term, a deposit is a liability you hold, and a fee is income the
  day you post. The second grid is the cheque register for this contract.
  Every row carries a posting date, a cheque number, a maturity date, the
  drawer's bank, the amount and a narration such as Rent, first installment.
  Notice that the cheque grid adds up to exactly the contract value. If it does
  not, posting is refused, because the register is how the money is tracked and
  it has to account for all of it. Nothing you have looked at so far has touched
  the ledger. The contract is still a draft, and a draft can be edited freely.
  Now select Post. Confirm the contract date. RentAxis writes one tenancy
  contract journal debiting rent receivable and crediting each line's own
  account, and one post-dated cheque journal for every row of the grid, moving
  the amount from rent receivable into post-dated cheques receivable. Open the
  Journals tab and read them. The contract status is now Active. Open the
  Recognition schedule tab. RentAxis has already planned how the rent will be
  earned, one row per calendar month, using the actual number of days in each.
  Nothing there is posted yet. Posting is deliberate: after a contract is
  posted it is never edited, only amended, which reverses the original and
  writes a fresh one.”

## 35 — Register and clear cheques

- Audience: finance admins and property managers; 2–3 minutes.
- Capture: open the cheque register, filter by status, bank a batch, clear one
  row, mark another returned, and replace the returned cheque with two rows.
- Narration: “This tutorial follows a cheque from the drawer's hand to the bank
  and back again. Open Finance and choose Cheques. Every cheque from every
  posted contract is here, with its status, its maturity date, the property and
  the renter. Use the filters to narrow the list to registered cheques maturing
  this month. Registered means the cheque is recorded and its journal is
  written, but the paper has not left the office. Select the rows you are taking
  to the bank and choose Cheque and cash collection. Enter the deposit date and
  the account you are banking into, then confirm. The selected rows move to
  deposited. Depositing is an operational step and writes no accounting entry,
  because nothing has changed about what you are owed. When the bank confirms a
  cheque, open its row and choose Clear. Enter the value date the bank gave you.
  RentAxis debits your bank account and credits post-dated cheques receivable.
  That is the moment the money becomes yours. Now take a cheque the bank has
  returned. Open its row and choose Mark returned, give the reason, and confirm.
  Because this cheque had already cleared, RentAxis reverses the bank side:
  it debits rent receivable and credits the bank. The amount is owed again, and
  the register shows it as due. Open Return and replace. A returned cheque is
  usually settled with new paper, and often more than one. Add two replacement
  rows, each with its own number, maturity date and amount, then save. Each
  replacement registers its own journal. If the replacements do not add up to
  the returned amount, the difference stays in rent receivable rather than
  disappearing. Penalties are separate and never automatic: a returned cheque
  proposes a penalty that finance approves, waives or reverses from the
  Penalties queue.”

## 36 — Month-end recognition

- Audience: finance admins; 2–3 minutes.
- Capture: show a contract's planned recognition schedule, preview a run to a
  chosen date, post it, then find the resulting journals in the general ledger.
- Narration: “Rent is collected in a handful of cheques but earned every day, so
  this tutorial closes a month. Start on a posted contract and open its
  Recognition schedule tab. RentAxis divided the term by its actual number of
  days to get a daily rate, then multiplied that rate by the real number of days
  in each calendar month. A twelve month contract that starts mid month
  therefore opens with a short period and closes with another, and the last row
  absorbs any rounding so the schedule adds up to the rent exactly. Every row
  shows its period, its day count, its amount and its status. Rows are planned
  until they are posted. Now open Finance and choose Recognition. Enter the date
  you are closing to, normally the last day of last month, and select Preview.
  RentAxis lists every planned row across every contract that ends on or before
  that date, with a total. Read the total before you post it; this is the rental
  income you are about to recognise for the period. Select Run. Each row becomes
  one journal dated the last day of its own period, debiting advance rent and
  crediting rental income, with the narration Advance rent adjustment and the
  month. Dating these entries at the period end rather than the first of the
  next month is deliberate, so a month's income falls inside the month it was
  earned in. Open the General Ledger and filter to the advance rent account. The
  balance falls by exactly what you just recognised, and the rental income
  account rises by the same amount. Recognition also runs automatically each
  night, so a backdated contract catches up on its own. Running it by hand is
  for closing a period on purpose, and for the first catch-up after you move
  your books across.”

## 37 — Tenant ledger

- Audience: finance admins and accountants; 2–3 minutes.
- Capture: open a renter's ledger, read each account, explain the running
  balance, drill into a journal, and compare the trial balance totals.
- Narration: “This tutorial reads a renter's account the way an accountant
  does. Open Finance and choose Tenant Ledger, then select the prepared renter.
  RentAxis shows one block per ledger account the renter has touched, and inside
  each block one row per entry. Every row carries the document date, the
  document number, the account on the other side of the entry, the narration,
  the debit or the credit, and the running balance after it. Start with rent
  receivable. The tenancy contract debited it with the whole contract value,
  then one post-dated cheque entry per cheque credited it back, so immediately
  after posting the balance is zero. That is correct and it is the point: what
  the renter owes lives in the cheque register, not in this account. A balance
  appears here only when something goes wrong, such as a returned cheque, which
  debits it again. Read the post-dated cheques receivable block. It rises with
  every cheque registered and falls with every cheque cleared, so its balance is
  the paper you are still holding. Read advance rent. The contract credited the
  whole year, and each month-end recognition debits back the portion earned, so
  it winds down to zero across the term. Select any document number to open the
  journal behind it. A journal shows every line, always balancing, and it cannot
  be edited or deleted, only reversed, which writes a mirror entry and links the
  two. Finally open Trial Balance. Choose a date and confirm that total debits
  equal total credits. That single check is what tells you the ledger behind
  every screen in this tutorial is sound.”

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
