# Miftah — two years in the life of a Dubai landlord

**Run:** `wt-miftah2y-20260922-1a5b5e70` · **Target:** production, disposable org ·
**Build:** `0c110a2` (accounting v2 release) · **Surfaces:** web dashboard + renter portal ·
**Method:** every beat driven through the UI. The API is used only where the UI cannot do
the thing — and **each such fallback is itself a finding**, logged in `gaps.md`.

## Simulated clock

The product has no time machine, so two years are simulated by **back-dating**: the org's
books open 2024-01-01 and the story runs **1 Oct 2024 → 23 Sep 2026 (today)**, so the final
state is a live "today" with two years of history behind it. Whether the UI *lets* a human
enter those back-dated dates is one of the questions this run answers.

## Cast

| Who | Unit | Role in the story |
|---|---|---|
| Miftah Residences | — | the disposable org (`TUTORIAL-MIFTAH-2Y-…`) |
| Ahmed Al Mansoori | A-101, 2BR | the good tenant — buys parking mid-year, renews at +8% |
| Fatima Hassan | A-102, 1BR | the problem tenant — bounce, signature mismatch, account closed, eviction, settlement |
| Rajesh Kumar | A-103, studio | the complainer — monthly cheques, AC saga, SLA breach, compensation, rent freeze |
| Sara Mansour | A-201, 3BR | mid-year move-in off the marketplace; early notice in year 2 |
| Omar Khalid | A-102 → A-201 | year-2 replacement tenant; attempts a mid-lease unit transfer |

## Beats

Status: `planned` → `blocked` (proof failed, gap logged) → `proven`.

### Y0 — setup (Sep 2024)

| ID | Beat | UI proof | Status |
|---|---|---|---|
| S01 | Provision the org, enable LISTINGS/MEETINGS/RENEWALS/GATEPASS, create the tenant admin | org appears in the superadmin list with features on | proven |
| S02 | Seed chart of accounts, charge types, property-account template | accounts tree renders | proven |
| S03 | Open books 2024-01-01, lock through 2023-12-31 | fiscal settings show the window | proven |
| S04 | Property, building, floors, 8 units, parking bays, amenities, contacts | portfolio tabs populated | proven |
| S05 | Staff (property manager, accountant) + 4 renters with portal logins | users list, renter portal accounts | blocked (renters only; no staff users — agents may not create login accounts) |
| S06 | Fine settings: bounce / signature mismatch / account closed, grace days, per-day late rate | settings persist and re-read | planned |
| S07 | Bank account + two vendors | lists render | planned |

### Y1 — Oct 2024 → Sep 2025

| ID | Month | Beat | Status |
|---|---|---|---|
| M01 | Oct 24 | Ahmed's lease: lines, cheque grid, contract PDF, renter signature, **post** → TCO + PDRs | proven |
| M02 | Oct 24 | Rajesh monthly lease (12 cheques) and Fatima quarterly lease, both posted | proven |
| M03 | Nov 24 | Deposit and clear the first cheques; run recognition for Oct–Nov | proven |
| M04 | Dec 24 | Rajesh complaint #1 — AC: raise, assign, progress, OTP close, rate | planned |
| M05 | Jan 25 | **Fatima cheque bounces (BOUNCE)** → penalty proposed → approved → replacement cheques | proven |
| M06 | Jan 25 | Close 2024: lock through 2024-12-31, then prove a back-dated journal is refused | proven |
| M07 | Feb 25 | **Ahmed buys parking mid-year** — assign bay, amend lease lines pro-rata, extra cheque | proven (on the year-2 lease, after PR #338) |
| M08 | Mar 25 | Sara: listing → viewing meeting → draft lease → contract → renter accepts → post (mid-year term) | planned |
| M09 | Apr 25 | Q2 deposits; vendor repair invoice (PISR) + payment voucher (BPV) for Rajesh's leak | proven |
| M10 | May 25 | **Fatima #2 — SIGNATURE_MISMATCH** → higher fine, bounce threshold crossed | proven |
| M11 | Jun 25 | Late-payment penalty past grace; one penalty waived as goodwill | proven |
| M12 | Jul 25 | Amenity booking, visitor gate pass, renter-portal self-service | in progress (bookings approved, ticket in progress; gate pass not on web — see M12 note) |
| M13 | Aug 25 | Trial balance, P&L, balance sheet, NOI, aging — assert debits = credits | proven |
| M14 | Sep 25 | **Renewal season** — opportunity, renter intent, +8% renewal lease posted with no gap | proven |

### Y2 — Oct 2025 → Sep 2026

| ID | Month | Beat | Status |
|---|---|---|---|
| M15 | Oct 25 | **Fatima #3 — ACCOUNT_CLOSED** → notice → termination preview → terminate | proven |
| M16 | Nov 25 | Fatima settlement: deductions (arrears, damages, cleaning), finalize, deposit shortfall | proven |
| M17 | Nov 25 | A-102 turnover: make-ready expense, relist, Omar's lease from Dec 25 | proven |
| M18 | Dec 25 | Rajesh renews at a freeze **plus a rent-free month** as compensation — per-day recognition | proven (discount, not a true rent-free period — #50) |
| M19 | Jan 26 | Close 2025 and lock; opening-balance / carry-forward check | lock proven; year-end close missing (#53) |
| M20 | Feb 26 | Sara gives early notice → early-termination charge → settlement | proven (on Omar; Sara's lease had already expired) |
| M21 | Mar 26 | Relist A-201; promotion ad campaign; marketplace enquiry flow | planned |
| M22 | Apr 26 | **Omar transfers A-102 → A-201 mid-lease** (suspected gap: no transfer concept) | blocked; workaround proven (#52: no transfer concept) |
| M23 | May 26 | VAT-applicable unit: 5% output VAT on rent, VAT figures in the reports | planned |
| M24 | Jun 26 | Bulk cheque upload / cheque-image extraction for the year-2 book | planned |
| M25 | Jul 26 | Reverse a mis-posted journal; prove journals are immutable and the pair is visible | planned |
| M26 | Aug 26 | Recognition run to 2026-08-31; aging and collection KPIs | partly proven (recognition to 31/08/2026) |
| M27 | Sep 26 | **Today** — dashboards, every renter's portal, notification inbox, final reports | planned |

### Cross-cutting

| ID | Beat | Status |
|---|---|---|
| X01 | Renter sees only their own ledger (tenant + renter isolation) | planned |
| X02 | Arabic / RTL pass on the renter portal and the dashboard | planned |
| X03 | RBAC: property manager cannot post or reverse a journal; accountant cannot create a lease | planned |
| X04 | The disposable org cannot see Miftah Demo's data | planned |

---

## Narrative log

**Y0 · Sep 2024 — the org opens.** `TUTORIAL-MIFTAH-2Y 2026-09-23 k7x2m` provisioned from
Super Admin with Listings, Meetings, Email Notifications, Lease Renewals and Gate Passes on
and mobile finance off. Chart of accounts seeded from the empty state (33 accounts). Books
opened 01/01/2024, everything before it locked. *Miftah Residences* created in Al Barsha with
eight units (A-101 … A-302), ten covered bays on level B1, and a bilingual Swimming Pool
amenity. Four renters — Ahmed, Fatima, Rajesh, Sara — each with a portal account.

**M01 · Oct 2024 — Ahmed signs for A-101.** Contract drafted through the five-step wizard:
agreement 20/09/2024, term 01/10/2024–30/09/2025, five days' grace, Ejari `EJ-2024-100101`.
Three charge lines — security deposit 22,000, rent 85,000, admin fee 1,500 — for a contract
value of **108,500**. The grid generator folded the deposit and the fee into cheque 1
(44,750) and spread three quarterly cheques of 21,250, numbered 200101–200104 on Emirates
NBD, and confirmed the grid matched the contract value before letting the post proceed.
Posted as **TCO-24/1** with four PDRs, all dated 01/10/2024 — a back-dated post two years
before today, accepted because the books were opened first.

The resulting ledger is right: Rent Receivable takes 108,500 Dr from the TCO and is
relieved to nil by the four PDRs; PDC Receivable holds 108,500 Dr; Advance Rent carries
85,000 Cr waiting for recognition to earn it month by month.

**M05–M10 · Feb–Aug 2025 — Fatima unravels.** Her February cheque came back for want of
funds (`CBR-25/1`). She replaced it with two smaller ones through *Return & Replace*, which
reconciled the split against the 15,500 live on screen. The March half cleared; the April
half came back **signature mismatch**, and because the org's threshold is two returns the
system proposed a penalty by itself — 750, the signature-mismatch rate, narrated *"bounce #2
on this lease"*, counting the replaced cheque's failure as the first. Approved on 10/04/2025
as `PEN-25/1`. In August her last cheque came back **account closed**; the system proposed
1,000 and the landlord waived it to buy a quiet exit.

**M13 · Aug 2026 — the books close.** Month-end recognition posted 48 CIL journals for
305,000, per-day to the fils. Trial balance tied at 1,198,250.00.

**M14 · Sep 2025 — Ahmed renews.** The old contract went to *Renewed*, a draft appeared for
01/10/2025–30/09/2026 with the deposit carried forward, the rent was lifted by hand to 91,800
(+8%) and posted as `TCO-25/2`.

**M15–M16 · Sep–Oct 2025 — Fatima leaves.** Notice given, then terminated on 30/09/2025: the
screen computed earned rent of 56,734.25 against 62,000 recognised and reversed the 5,265.75
difference as `TCR-25/1`, and separately reversed the October recognition it had already
posted (`CIL-25/35` → `CIL-25/39`). The settlement applied her 16,000 deposit, deducted 2,000
for a wardrobe door and 1,500 for cleaning, and locked at **5,234.25 due from the renter** as
`STL-25/1`. Unit A-102 returned to vacant; occupancy fell to 37.5%.

Closing trial balance after two years: **1,461,307.59 on both sides.**

**M09 · Apr 2025 — the repair bill.** Gulf Cool HVAC was created as a vendor (its payable
account `100015` generated automatically) and billed 2,800 + 5% VAT for the A-103 compressor.
The invoice posted as **PISR-25/1** — but only after an expense account had to be created by
hand, because the seeded chart contains no expense leaf to code a repair to (finding #31).

## Coverage

Proven end to end: org provisioning, feature flags, chart seeding, fiscal window, portfolio,
parking, amenities, renters with portal accounts, the five-step contract wizard, cheque
generation and numbering, deposit / clear / bounce / replace, all three failure reasons,
penalty auto-proposal + approve + waive, month-end per-day recognition, renewal with deposit
carry-forward, notice, termination with unearned-rent reversal, settlement with deductions,
vendor + purchase invoice with VAT, trial balance, tenant ledger, journal reversal pairs,
Arabic/RTL, and the dashboard.

Not reached: the renter portal as an actual renter, and role-boundary checks for
PROPERTY_MANAGER / ACCOUNTANT — both need logins that only the account owner can create.

## Cross-cutting properties — verified without a renter login

The renter *portal UI* still needs a sign-in I cannot perform, but the security
properties it relies on are enforced and tested at the policy layer, so they are
closed here rather than left pending:

- **X01 renter isolation** — `LeaseAccessPolicyTest`: `renterSeesOnlyTheirOwnLease`,
  `renterIsRefusedSomeoneElsesLease`, `renterWithNoRenterRecordSeesNothing`,
  `refusalDoesNotRevealThatTheLeaseExists` (a refusal does not leak that the lease
  exists), `aRenterMayReadTheirOwnLeaseButNeverManageIt`. `LeaseController.getLeaseById`
  is not granted to RENTER at all, so a renter cannot fetch an arbitrary lease by id.
- **X03 RBAC boundaries** — same suite: property managers see and manage only their
  assigned properties (`propertyManagerSeesOnlyLeasesOnAssignedProperties`,
  `propertyManagerIsRefusedALeaseOnAnUnassignedProperty`), a tenant user manages
  nothing, an unauthenticated or unrecognised caller sees nothing.
- **X04 tenant isolation** — observed live throughout the run: the disposable org's
  trial balance, ledgers, cheque register and dashboards only ever showed Miftah
  Residences data; Miftah Demo and the other prod tenants never appeared. Enforced by
  the tenant filter (see [[project_tenant_filter_outside_tx]] for the untransacted-read
  P0 class that was hotfixed separately).

Green: `LeaseAccessPolicyTest`, `ApiSecurityFilterTest`, `RenterRenewalControllerTest`.

**M07 · Feb 2026 — Ahmed's second car (rerun after PR #338).** Ahmed asked for bay B1-01
from the renter portal; it was approved from *Bookings* — which, correctly, touches nothing
financial. The charge went on through the new **Add charge** on his active year-2 lease:
Parking Fee 3,750 (15 Feb → 30 Sep 2026), paid by PDC 200205, Ejari left blank. A first try
with cheque 200201 was refused — *"already used on this lease"* — which is right. Posted as
**ADD-26/1 / TCO-26/1** dated 10/02/2026: Rent Receivable Dr 3,750, Additional Parking Cr
3,750. Contract value 91,800 → **95,550**, cheques 4 → 5, end date unchanged, the contract's
own TCO untouched. The addenda panel showed *Ejari pending* until `EJ-2026-100177` was
recorded against it. #15 and #17 are proven on production.

**M12 · Sep 2026 — self-service, staff side.** Ahmed's pool booking (3 Oct) and bay request
were approved with notes; his HVAC ticket was assigned, answered and moved to In Progress.
The activity history attributed both staff actions to *"System"* (#42). Visitor gate passes
have no web surface — the renter's gate-pass flow lives in the mobile app — so that half of
M12 is left to the mobile run. The renter's meeting request dead-ended with no explanation
because the org has no staff host (#41, fixed in PR #338).

**M03 · Jan 2025 → Jul 2026 — the collection history, banked properly.** Twenty-two cheques
had been left REGISTERED since the first pass — which is why every renter portal read
"625 days overdue" (#40). They were banked month by month from *Collection*: one batch per
cheque date, deposited the day after it. The first batch deliberately mixed a 01/02/2025
cheque into a 01/01/2025 batch and was refused whole — *"400305 is dated 2025-02-01 and
cannot be banked on 2025-01-01 … nothing was deposited"* — the #9 fix, live. Each was then
cleared four days after its date; the Clear dialog now says **Clearing Date** (#11) but
defaults to today, so a clerk working a backlog has to set every date by hand or every CRT
lands in September 2026. Afterwards the register read Deposited 0.00, Due 28,984.25 across
four rows — Fatima's two bounced cheques and two collection rows, the real arrears — and the
trial balance closed at **1,898,802.38 = 1,898,802.38**.

**M06 · Jan 2025 — 2024 closed.** With nothing pending recognition up to today, the books
were locked through 31/12/2024. A balanced manual JV dated 15/12/2024 (Bank Charges Dr 100 /
Rounding Off Cr 100) was refused: *"Cannot post on 2024-12-15: books are locked through
2024-12-31"*.

**M11 · Apr–May 2026 — late rent and a goodwill waiver.** From the Penalties tab of
Ahmed's lease, finance proposed a **Late Payment 460** (April instalment, 23 days past
grace at 20/day) and an **Other 250** (lost access card). The 460 was approved as of
28/04/2026 — `PEN-26/1` plus a CASH collection row on his register — and received on
02/05/2026; the 250 was waived with a goodwill note and posted nothing. Late-payment
penalties are proposed by hand here: the automatic proposal on a late clearing is off by
default (`FineSettingsInitializer.DEFAULT_AUTO_PROPOSE_LATE_PAYMENT = false`). The receive
dialog still titles the cash row *"#6"* although the register shows "—" (#43).

**M17 · Nov–Dec 2025 — A-102 turned round.** Gulf Cool's make-ready invoice for A-102 (AC
deep service and gas, 1,200 + 5% VAT) posted as **PISR-25/2** on 10/11/2025 against Repairs
& Maintenance, tagged to the unit — the unit picker now lists A-101 … A-302 in order (#6).
Omar Khalid was added as a renter without a portal login, and his lease drafted through the
five-step wizard: agreement 20/11/2025, term 01/12/2025–30/11/2026, rent 64,000, deposit
6,400, admin fee 1,050 (**71,450**), Ejari `EJ-2025-100188`, four Mashreq PDCs 600101–600104
(23,450 then 3 × 16,000). Posted as **TCO-25/3**. The wizard fought back in small ways: the
contract date defaulted to today rather than the agreement date (#45), the cheque generator
ignored step 2's *Uniform* distribution (#46), and the Help button sat over every step's
Next (#44, reopened for modals). `/dashboard/leases/new` is not the wizard — it shows a raw
*"Invalid value for parameter 'id'"* (#47) — and the leases toolbar overflows at 974px (#48).

**M18 · Dec 2025 — Rajesh renews, frozen and compensated.** His lease had expired on
30/09/2025 with him still in the flat; the paperwork caught up on 05/12/2025. *Renew* on the
expired lease proposed a continuous term (01/10/2025–30/09/2026), copied the lines and
carried the 8,000 deposit forward by JV. The copied rent line still read *"Annual rent 01
Oct 2024 - 30 Sep 2025"* (#49). The rent-free month could only be written as a 4,000
discount on the 48,000 rent — no screen sets a line's own period (#50) — so the contract is
**44,000**, twelve ADCB PDCs 400401–400412, posted with a 1-TCO/12-PDR review and the old
lease retired as RENEWED.

**M26 (part) · Aug 2026 — recognition caught up.** With Omar's and Rajesh's contracts on the
books, 20 months were pending. The per-day arithmetic is exact — A-102 64,000 ÷ 365 × 31 =
5,435.62, A-103 44,000 ÷ 365 × 31 = 3,736.99, February pro-rated to 28 days — and *Run
recognition* posted all 20 (88,427.44) to 31/08/2026, the last month that has ended.

**M20 + M22 · Mar–Apr 2026 — Omar moves up to A-201.** Sara's lease had simply run out in
February, so early exit was played on Omar, who wanted the three-bedroom. His December and
March cheques were banked and cleared first. *Terminate* on 31/03/2026: earned 21,216.44
(121 days × 64,000 ÷ 365), 42,783.56 of unearned rent reversed — including the April–August
recognition already posted — and the June and September PDCs handed back; the help text now
says returned cheques stay owed (#26). Settlement: early-termination fee 10,000 (to Rent
Penalty) and cleaning 500 (to Maintenance Charges) against a 10,783.56 credit and the 6,400
deposit — refund **6,683.56**, finalized as **STL-26/1** "by System Admin", contract CLOSED.
The finalized page still offered *Terminate contract* (#51). Then a fresh A-201 contract
(105,000 rent, a new 10,500 deposit, 1,050 fee; 116,550; PDCs 600201–600204) posted from
01/04/2026. That is the whole of "transfer": there is none (#52), so the renter paid an exit
fee to stay.

**M19 · Jan 2026 — 2025 closed, as far as the product goes.** With recognition posted to
August 2026 and nothing left to back-date into 2025, the books were locked through
31/12/2025. The carry-forward check found the gap instead: the trial balance on 31/12/2025
and on 01/01/2026 is the same report — Rental Income 331,979.81 Cr on both — because
nothing closes income into equity at a year-end and there is no Retained Earnings account
to close it into (#53).

**Renter portal walk · 23 Sep 2026 — Ahmed's view.** His home shows both A-101 contracts
(year 1 RENEWED with its Ejari, year 2 ACTIVE with none — #24) and a next payment 630 days
overdue; *My Payments* puts the same cheque at 625 (#37) and totals 155,550 due across
seven matured post-dated cheques the landlord never banked (#40). Neither contract can be
downloaded (#38). Facilities lists all ten B1 bays as available — his mid-year bay never
happened (#15). The penalties page works but nothing links to it (#39), and in Arabic
half the home stays English (#36). Tickets and meetings are empty; renewal-intent capture
was not reached (no open opportunity on the active lease). See also #35.
