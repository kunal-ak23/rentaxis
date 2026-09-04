# App Review Information — Miftah Resident, Manager and Security

Paste-ready answers to Apple's **Guideline 2.1 – Information Needed** request, plus the
disclosures that pre-empt a **Guideline 5.6 – Developer Code of Conduct** ("features hidden
during review") finding. Prepared 5 September 2026 from the source tree; items marked
**OWNER ACTION** need the account holder before submission.

The three apps are one product seen by three roles. Say so in every Notes field — the
single most common reason a role-based B2B app gets 5.6 is that the reviewer sees a third
of it and later discovers the rest.

---

## What changed in the builds that answer this rejection

Every point below is now in the source and must be in the resubmitted binaries:

| Requirement | Where it now lives |
| --- | --- |
| In-app **account deletion** (5.1.1(v)) | Resident: Profile → below Sign out. Manager: Profile → below Sign out. Security: gear → Settings sheet → below Sign out. Backend `DELETE /api/v1/account` acts only on the authenticated principal. |
| Deletion **revokes Sign in with Apple tokens** (5.1.1(v)) | Resident/Manager send the credential's `authorizationCode`; backend exchanges it for a refresh token and revokes it on deletion. Needs the Apple key — **OWNER ACTION**, see "Server configuration". |
| **Privacy Policy / Terms / Data-deletion links in-app** | Resident and Manager: Profile → "Privacy & terms" card, and Manager More → Account. Security: Settings sheet. |
| **Version on screen matches the build** | About/footer now read the bundle version; Resident previously showed 1.1.0 while shipping 1.3.0. |
| Reviewer can **sign in to Security without a UAE SIM** | Firebase test phone number — **OWNER ACTION**, procedure below. |

---

## 1. Screen recording — what to record, per app

Record on the registered physical iPhone on the latest iOS, starting from app launch. One
continuous recording per app; the checklist is the order the reviewer will look for.

### Miftah Resident (`com.rentaxis.renter`)
1. Cold launch → login screen. Sign in with the **Resident** credentials below (email + password).
2. Tap **Sign in with Apple** once and cancel, to show it exists (see §4 on why a reviewer's own Apple ID cannot create an account).
3. Home → rent schedule, cheque status, receipts.
4. Explore → browse listings; tap "near me" so the **location prompt** appears; decline it; show the list still works.
5. Services → create a maintenance request; attach a photo so the **camera / photo prompt** appears.
6. Services → create a visitor pass (QR + numeric code).
7. Notifications; switch language to Arabic and back (RTL).
8. Profile → Privacy Policy / Terms / Account & data deletion links open.
9. Profile → **Delete account** → confirm → app returns to login. (Use the disposable reviewer account, not the demo renters.)

### Miftah Manager (`com.rentaxis.manager`)
1. Cold launch → login screen → sign in with the **Manager** credentials.
2. Sign in with Apple shown and cancelled, as above.
3. Dashboard → properties → a unit → its lease → payment schedule.
4. Cheques → scan a cheque (**camera prompt**; a printed sample cheque image is fine).
5. Tickets, Listings (publish/edit), Meetings, Renters, Staff.
6. More → Gate pass approvals, Security guards, Gate access policy.
7. More → Finance (accounts, bank accounts, vendors, reports).
8. Arabic switch; More → Privacy Policy / Terms; Profile → **Delete account** flow with the disposable reviewer account.

### Miftah Security (`com.rentaxis.security`)
1. Cold launch → phone screen → enter the **test phone number** → enter the **fixed code** (no SMS is sent for a Firebase test number).
2. Expected-today visitor board.
3. Scan → **camera prompt** → scan a pass QR (create one in Resident first) → allowed/rejected result screen.
4. Approvals queue; Walk-in → capture visitor details and photo.
5. Arabic switch; gear → Settings → Privacy/Terms links; **Delete account** flow with a second test guard, not the one the reviewer will use.

Do not show account *registration*: there is none (see §3). Say that in the Notes.

---

## 2. Devices and operating systems tested

| Device | OS | Evidence |
| --- | --- | --- |
| iPhone 15 (physical, registered development device) | **OWNER ACTION: record the exact iOS version installed when the recordings are made** | Automatic signing and App Store IPA export verified on this device (submission package) |
| iPhone 16 Pro simulator | iOS 18.x — **OWNER ACTION: confirm from Xcode** | Tutorial/QA captures under `tutorials/qa/review` |
| iPad (Air 13", simulator) | **OWNER ACTION** | Required because iPad availability is enabled; run each app once in landscape and portrait |

Android is not part of this submission but the same builds ship on Google Play (see `docs/play-store`).

---

## 3. What the apps do and for whom

**Miftah** is a property-management service for landlords and property operators in the UAE.
Accounts are **issued by a participating property organisation** — there is no public
sign-up in any of the three apps. Each app is one role's view of the same tenant-isolated
backend:

- **Miftah Resident** — for tenants of participating properties: rent schedule and cheque
  status, receipts, maintenance requests with photos, lease documents and contract signing,
  visitor passes (QR/numeric), amenity and parking bookings, meetings, penalties,
  notifications, browsing available homes (optionally by location), promotions.
- **Miftah Manager** — for the organisation's administrators and property managers:
  portfolio dashboard, properties/units, leases and renewals, cheque capture (camera) and
  payment tracking, maintenance tickets, listings, meetings, renters and staff, finance
  (accounts, bank accounts, vendors, reports), gate-pass approvals, guards and access policy.
- **Miftah Security** — for security guards posted to a property: expected visitors, QR /
  numeric pass validation with a clear allowed/rejected verdict, approval queue, walk-in
  registration with photo, admission.

Problem solved: UAE tenancy runs on post-dated cheques, paper visitor logs and WhatsApp.
Miftah puts collections, maintenance, documents and gate access on one bilingual (English /
Arabic, full RTL) system so residents, operators and guards see the same record.

Target audience: adults (18+) who are tenants, staff or contracted guards of a participating
property organisation. Free; no in-app purchases, subscriptions, advertising or tracking.

---

## 4. How to sign in and reach the main features

**Everything below is on the "Al Ashram Demo Account" organisation, a purpose-built demo
tenant on production with every feature switch enabled.** It is not a customer.

| App | Sign in with | Credentials |
| --- | --- | --- |
| Miftah Manager | Email + password | `admin@alashramdemo.com` / `<demo password — scripts/seed_demo_tenant.out.json, gitignored>` (organisation administrator — sees every Manager feature) |
| Miftah Resident | Email + password | `ahmed@alashramdemo.com` / `<demo password — scripts/seed_demo_tenant.out.json, gitignored>` (active lease with cheques, tickets and passes). Also `fatima@`, `rajesh@`, `sara@alashramdemo.com`, same password. |
| Miftah Security | Phone number + code | **OWNER ACTION** — Firebase test number, e.g. `+971 50 000 0001` with fixed code `123456`; see "Reviewer access for Security" below |
| Disposable accounts for the deletion demo | as above | **OWNER ACTION** — create one extra renter, one extra staff user and one extra guard on the demo tenant purely to be deleted on camera |

Notes to put in App Store Connect verbatim:

> Miftah has no public registration: accounts are issued by a participating property
> organisation, which is why there is no sign-up screen. Please use the review accounts
> above. **Sign in with Apple links to an existing invited account by email** — it does
> not create accounts, so signing in with your own Apple ID will be declined with
> "No active account matches this Apple ID"; that is expected. Account deletion is
> available in every app under Profile / Settings and removes the sign-in and personal
> profile; tenancy and access records are retained by the property organisation as our
> Privacy Policy states.

Reviewer access for Security (**OWNER ACTION**, ~10 minutes):
1. Firebase console → project `rent-axis-493307` → Authentication → Sign-in method →
   Phone → **Phone numbers for testing** → add `+971500000001` with code `123456`.
   Firebase sends no SMS and needs no APNs/reCAPTCHA for a test number.
2. Sign in to the web as `admin@alashramdemo.com` → Security guards → add a guard with that
   exact phone, active, assigned to the demo property. (Or `POST /api/v1/gatepass/guards`.)
3. Put the number and code in the Notes field. Keep the test number configured for the life
   of the listing — Apple re-reviews updates.

---

## 5. External services and platforms

| Service | Used by | Purpose |
| --- | --- | --- |
| Microsoft Azure (VM, Blob Storage, Communication Services) | all | Hosting the API, document/photo storage, transactional email |
| Azure OpenAI | Manager | Reading cheque details from a captured image (no user content is used for training) |
| Firebase Authentication (phone) | Security | Guard sign-in by SMS code |
| Firebase Cloud Messaging / APNs | Resident, Security | Push notifications |
| Google Maps SDK | Resident, Manager | Listing locations and nearby search |
| Sign in with Apple | Resident, Manager | Optional sign-in for an already-invited account |
| Payment processors | — | OWNER ACTION: a payment-gateway reference was found in the mobile source — describe it here |

No analytics or advertising SDKs. No AI features act on the user's behalf.

---

## 6. Regional differences

None in behaviour. The apps are offered in the United Arab Emirates only because the
service is a UAE tenancy product (AED, Emirates, Ejari references, UAE cheque practice).
Within that, every user sees the same features for their role; language (English/Arabic)
is a user setting, not a region gate. Guard SMS sign-in is allowed for UAE and India
numbers by the Firebase SMS region policy, which only affects where a real SMS can be
delivered — the reviewer's test number bypasses SMS.

---

## 7. Regulated industry / protected material

Miftah is property-management software; it is not a bank, payment institution or licensed
real-estate broker and takes no payments itself. Cheque and rent screens display records
that the property organisation enters and owns. No third-party protected material is
shipped in the apps; property, listing and promotion content is uploaded by the account
holder's organisation, which warrants its rights to it (content-rights answer in the
submission package).

---

## Pre-empting Guideline 5.6 — nothing is hidden, here is everything that varies

Say this in the Notes. Reviewers raise 5.6 when a role-based app behaves differently
later than it did on review day; disclosing the mechanisms up front is the defence.

1. **Role-based navigation.** Each app shows the screens for the signed-in role only
   (Resident: renter; Manager: administrator vs property manager — a property manager does
   not see Finance or Staff; Security: guard). The review Manager account is an
   *administrator* and therefore sees the complete Manager app.
2. **Per-organisation feature switches.** The backend has five organisation-level
   toggles an operator can turn on for a customer: Listings, Meetings, Email notifications,
   Lease renewals, Gate pass. **All five are enabled on the review organisation** (email
   notifications only affects outbound email, not any screen). No screen is hidden by a
   flag — a disabled feature returns an empty list. There are no A/B tests, no remote
   config, no time- or geo-triggered features.
3. **Minimum-version gate.** On launch each app asks `GET /api/v1/public/app-version`
   whether the installed build is still supported. It fails open (any error or timeout =
   proceed) and is currently inert (minimum build = 0). If a future release ever sets a
   floor, older builds show an "Update required" screen with the App Store link — the same
   pattern as any forced-update prompt, and it cannot reveal or enable features.
4. **Sign in with Apple** links to an invited account; it never creates one (see §4).
5. **Nothing changes after approval.** The backend is a single production deployment
   serving both the App Review accounts and customers; there is no review-mode build,
   flag or server switch. Any behaviour the reviewer sees is the behaviour customers get.

---

## Server configuration the account holder must complete (OWNER ACTION)

Sign in with Apple token revocation on account deletion needs the team's Sign in with Apple
key. Until it is set the backend logs "revocation disabled" and deletion still completes,
but Apple's 5.1.1(v) expects revocation to be in place:

1. Apple Developer → Certificates, Identifiers & Profiles → **Keys** → create a key with
   *Sign in with Apple* enabled (or reuse the existing one) → note the **Key ID**; download
   the `.p8` once.
2. Add GitHub Actions secrets: `APPLE_SIGNIN_TEAM_ID` (`AR3Y2NZTTQ`), `APPLE_SIGNIN_KEY_ID`,
   and `APPLE_SIGNIN_PRIVATE_KEY_BASE64` = `base64 < AuthKey_XXXX.p8 | tr -d '\n'`.
3. Redeploy. `docs/runbooks/firebase-phone-auth-setup.md` §7 covers the Security test number.
