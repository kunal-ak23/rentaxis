# Booking Journal Vouchers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Approving a parking or amenity booking charges the renter by a `JV` to the right income account, for a fee that scales with the booking's days or months, posted upfront.

**Architecture:** Booking fees stay `PenaltyAssessment` rows with reason `BOOKING_FEE`, raised by `BookingFeeService.chargeOnApproval`. The income account is chosen in `BookingFeeService` and stored on the charge (`incomeAccountId`, already honoured by `PenaltyAssessmentService.approve`). A server quote endpoint becomes the single home of the fee maths for web and mobile.

**Tech Stack:** Spring Boot / Java 21, Liquibase YAML, PostgreSQL (Testcontainers via `AbstractPostgresIT`), Next.js + vitest, Flutter.

**Spec:** `docs/superpowers/specs/2026-11-19-booking-journal-vouchers-design.md`

## Global Constraints

- Upfront only. No Unearned, no recognition rows for bookings.
- Every booking journal is dated on the day it is posted, inside the manual-date window (`PostingDatePath.PENALTY_CHARGE`). Never in a locked month.
- VAT 5% on the full fee at approval, through the existing `approve` path.
- Charges posted before this change keep their `PEN` journals. Nothing is re-posted.
- `PER_HOUR` stays amenity-only. `PER_MONTH` is parking-only. `PER_DAY` is both.
- Money rounds to 2 places, `RoundingMode.HALF_UP`.
- Bilingual: every new label has English and Arabic (`web/messages/en.json`, `web/messages/ar.json`, Dart `ar ?` ternaries).
- Changesets continue from `189-penalty-income-account.yaml` and are included in `db/changelog/db.changelog-master.yaml`. Each has a rollback.

## Review Focus

1. **`PER_MONTH` from the 29th–31st:** a booking from 31 Jan must count months as `start.plusMonths(n)`, not chained additions, so 31 Jan–30 Mar is 2 months (31 Jan + 2 = 31 Mar, minus 1 day = 30 Mar). Test in Task 1.
2. **Undated parking request approved:** approval fills start = today and end = contract end; the stored quote was taken on missing dates and must be re-taken. Test in Task 3.
3. **Income account deactivated or moved after it was set on a bay:** approval must refuse with `booking.incomeAccount` and post nothing, not post to a dead account. Test in Task 3.
4. **Bay fee changed while a request is pending:** approval charges the stored quote, not the new fee. Test in Task 3.
5. **Staff booking for a renter who is not on that unit's live lease:** refused with `booking.noLease`, nothing created. Test in Task 5.

---

### Task 1: Per-day and per-month fees

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/BookingFeeService.java` (`TYPES`, `validate`, `quote`)
- Create: `backend/src/main/resources/db/changelog/changesets/190-booking-fee-periods.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/BookingFeeQuoteTest.java` (new, plain JUnit)

**Interfaces:**
- Produces: `BookingFeeService.TYPES = {"FREE","PER_BOOKING","PER_HOUR","PER_DAY","PER_MONTH"}`; `static void validate(String type, BigDecimal amount, boolean parking)`; `static BigDecimal quote(String type, BigDecimal amount, LocalDate start, LocalDate end, LocalTime from, LocalTime to)` (new overload; the `BookingRequest` overload delegates to it).

- [ ] **Step 1: Write the failing tests** in `BookingFeeQuoteTest`:

```java
@Test void perMonthWholeMonths() {
    assertThat(q("PER_MONTH", "300", "2026-12-01", "2027-01-31")).isEqualByComparingTo("600.00");
    assertThat(q("PER_MONTH", "300", "2026-12-15", "2027-02-14")).isEqualByComparingTo("600.00");
}
@Test void perMonthLeftoverDaysAtThatMonthsDayRate() {
    assertThat(q("PER_MONTH", "300", "2026-12-01", "2027-01-10")).isEqualByComparingTo("396.77"); // 300 + 10×300/31
    assertThat(q("PER_MONTH", "300", "2026-12-01", "2026-12-10")).isEqualByComparingTo("96.77");
}
@Test void perMonthFromTheEndOfAMonthDoesNotDrift() {
    assertThat(q("PER_MONTH", "300", "2027-01-31", "2027-03-30")).isEqualByComparingTo("600.00");
}
@Test void perMonthLeapFebruary() {
    assertThat(q("PER_MONTH", "290", "2028-02-01", "2028-02-10")).isEqualByComparingTo("100.00"); // 10×290/29
}
@Test void perDayCountsBothEnds() {
    assertThat(q("PER_DAY", "40", "2026-12-01", "2026-12-03")).isEqualByComparingTo("120.00");
}
@Test void perMonthIsParkingOnlyAndPerHourAmenityOnly() {
    assertThatThrownBy(() -> BookingFeeService.validate("PER_MONTH", BigDecimal.TEN, false)).hasMessageContaining("PER_MONTH");
    assertThatThrownBy(() -> BookingFeeService.validate("PER_HOUR", BigDecimal.TEN, true)).hasMessageContaining("PER_HOUR");
    BookingFeeService.validate("PER_DAY", BigDecimal.TEN, true);
    BookingFeeService.validate("PER_DAY", BigDecimal.TEN, false);
}
// q(type, amount, start, end) = BookingFeeService.quote(type, new BigDecimal(amount), LocalDate.parse(start), LocalDate.parse(end), null, null)
```

- [ ] **Step 2: Run, expect FAIL** (no such overload): `cd backend && ./gradlew test --tests '*BookingFeeQuoteTest'`

- [ ] **Step 3: Implement.** `PER_DAY` = amount × inclusive days. `PER_MONTH`:

```java
int n = 0;
while (!start.plusMonths(n + 1).minusDays(1).isAfter(end)) n++;
LocalDate rest = start.plusMonths(n);
BigDecimal total = amount.multiply(BigDecimal.valueOf(n));
if (!rest.isAfter(end)) {
    long days = ChronoUnit.DAYS.between(rest, end) + 1;
    total = total.add(amount.multiply(BigDecimal.valueOf(days))
            .divide(BigDecimal.valueOf(rest.lengthOfMonth()), 10, RoundingMode.HALF_UP));
}
return total.setScale(2, RoundingMode.HALF_UP);
```

A null `end` means `end = start`. `validate` refuses `PER_MONTH` when `!parking`, keeps refusing `PER_HOUR` when `parking`; error code stays `booking.feeType`.

- [ ] **Step 4: Changeset 190.** Drop and re-add `ck_spot_fee` with `('FREE','PER_BOOKING','PER_DAY','PER_MONTH')` and `ck_amenity_fee` with `('FREE','PER_BOOKING','PER_HOUR','PER_DAY')`, both keeping `AND fee_amount >= 0`. Rollback restores the `143-booking-fees.yaml` definitions. Include in master.

- [ ] **Step 5: Run** `./gradlew test --tests '*BookingFeeQuoteTest' --tests '*BookingFeeIT'`. Expected: PASS.

- [ ] **Step 6: Commit** `feat(bookings): per-day and per-month booking fees`

---

### Task 2: Income account on bays and amenities, and the AMENITY_INCOME role

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/ledger/IncomeLeafRule.java`
- Modify: `core/service/penalty/PenaltyAssessmentService.java` (replace private `requireIncomeLeafFor` with `IncomeLeafRule.require`)
- Modify: `domain/entity/enums/AccountRole.java` (add `AMENITY_INCOME`, property-scoped by default)
- Modify: `core/service/ledger/PropertyAccountService.java` (template row `"Amenity Income - {property}"` under `C-01-02`; `ROLE_LABEL_AR` entry `"إيرادات المرافق"`)
- Modify: `core/service/report/ReportLines.java` (role line `"Amenity income"` / `"إيرادات المرافق"` after parking; add to the INCOME case of `naturalType`)
- Modify: `domain/entity/ParkingSpot.java`, `domain/entity/PropertyAmenity.java` (`UUID incomeAccountId`)
- Modify: `api/dto/ParkingSpotCreateRequest.java`, `ParkingSpotUpdateRequest.java`, `ParkingSpotDTO.java`, `AmenityCreateRequest.java`, `AmenityUpdateRequest.java`, `AmenityDTO.java` (add `UUID incomeAccountId` as the last component; the two update requests also add `Boolean clearIncomeAccount` after it; keep every existing compatibility constructor compiling by passing `null`)
- Modify: `core/service/FacilityService.java` (validate on create/update)
- Create: `backend/src/main/resources/db/changelog/changesets/191-booking-income-account.yaml`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/FacilityIncomeAccountIT.java`

**Interfaces:**
- Produces: `IncomeLeafRule.require(Account account, UUID propertyId)` throws `BusinessRuleViolationException` with code `booking.incomeAccount` when the account is a group, inactive, not `AccountType.INCOME`, or owned by another property. (`PenaltyAssessmentService` keeps its current messages by passing the lease's property id.)
- Produces: `ParkingSpot.getIncomeAccountId()`, `PropertyAmenity.getIncomeAccountId()`; `AccountRole.AMENITY_INCOME`.

- [ ] **Step 1: Write failing ITs** in `FacilityIncomeAccountIT` (fixture setup as in `BookingFeeIT`):
  - `aBayKeepsTheIncomeLeafItWasGiven`: create a bay with `incomeAccountId` = the property's `PARKING_INCOME` leaf; reload; equals.
  - `aGroupAnExpenseOrAnotherPropertysLeafIsRefused`: three updates, each throws code `booking.incomeAccount`; bay unchanged.
  - `aNewPropertyGetsAnAmenityIncomeLeaf`: create a second property; `accountResolver.resolve(AMENITY_INCOME, p2)` has name `"Amenity Income - " + p2 name` and parent code `C-01-02`.
  - `existingContractJournalVoucherStillRefusesAGroup`: `PenaltyAssessmentService.raiseJournalVoucher` with a group account still throws (message unchanged).

- [ ] **Step 2: Run, expect FAIL.**

- [ ] **Step 3: Implement** entity fields, DTOs, `IncomeLeafRule`, `FacilityService` validation (only when `incomeAccountId != null`; null clears it on update only when a new `Boolean clearIncomeAccount` is true — otherwise null = unchanged, matching patch semantics).

- [ ] **Step 4: Changeset 191**, following `138-interproperty-clearing.yaml`:
  - `ALTER TABLE parking_spots ADD COLUMN income_account_id UUID REFERENCES accounts(id) ON DELETE SET NULL`; same for `property_amenities`.
  - Insert an `AMENITY_INCOME` row into `property_account_template_rows` (`name_pattern 'Amenity Income - {property}'`, parent = the tenant's `C-01-02`) for tenants that already have template rows and none for this role.
  - No per-property leaves here. Rollback drops the columns and deletes the template rows.

- [ ] **Step 5: Run** `./gradlew test --tests '*FacilityIncomeAccountIT' --tests '*FacilityServiceTest' --tests '*PenaltyAssessment*'`. Expected: PASS.

- [ ] **Step 6: Commit** `feat(ledger): income account on parking bays and amenities, AMENITY_INCOME role`

---

### Task 3: Charge bookings as journal vouchers to the right account

**Files:**
- Modify: `domain/entity/enums/PenaltyReason.java` (add `boolean requestedByRenter()`: true for `JOURNAL_VOUCHER`, `BOOKING_FEE`)
- Modify: `core/service/penalty/PenaltyAssessmentService.java` (`approve`: the three `== JOURNAL_VOUCHER` checks for doc type, row label and notification use `requestedByRenter()`; `proposeFromSource` gains an overload with `UUID incomeAccountId`)
- Modify: `core/service/BookingFeeService.java` (`chargeOnApproval`)
- Modify: `core/service/BookingService.java` (`approve`: re-quote when it filled in dates)
- Modify: `core/service/BookingNotificationService.java` (approved message adds the fee incl. VAT and "Pay it in Payments" / "ادفعها من المدفوعات" when `chargeId != null`)
- Test: `backend/src/test/java/com/datagami/rentaxis/golden/GoldenBookingJournalVoucherIT.java` (`@Tag("golden")`), update `BookingNotificationServiceTest`

**Interfaces:**
- Consumes: Task 1 `quote(...)`, Task 2 `IncomeLeafRule.require`, `getIncomeAccountId()`.
- Produces: `PenaltyAssessmentService.proposeFromSource(ProposePenaltyRequest r, UUID byUser, String sourceType, UUID sourceId, boolean system, UUID incomeAccountId)`; `BookingFeeService.incomeAccountFor(BookingRequest b, UUID propertyId) -> UUID`.

- [ ] **Step 1: Write failing golden tests** (fixture as `BookingFeeIT`; a parking bay `PER_MONTH` 300, VAT lease):
  - `twoMonthParkingPostsOneJvToAdditionalParking`: book 1st of next month to last day of the month after; approve. Exactly one journal with source `PENALTY` / charge id: `doc_type = 'JV'`, `entry_date = today`, Dr receivable 630.00, Cr the property's `PARKING_INCOME` leaf 600.00, Cr `OUTPUT_VAT` 30.00. Collection row amount 630.00, narration starts `"Booking fee - Parking "` and contains both dates. No `PENALTY_INCURRED` notification row.
  - `cancelledBeforeStartNetsToZero`: renter cancels; sum of debit−credit per account over journals with that charge's source is 0 for every account.
  - `paidThenStaffCancelledLeavesCredit`: receive the row, staff-cancel; receivable delta −630.00, one `CREDIT_NOTE`.
  - `amenityWithOwnLeafCreditsIt` / `amenityWithoutLeafCreditsAmenityIncome` / `amenityOnPropertyWithoutAmenityLeafFallsBackToOtherIncome` (delete the property's `AMENITY_INCOME` mapping first).
  - `undatedParkingRequestIsRequotedOnApproval` (Review Focus 2): insert a pending parking request with null dates and `fee_amount` 0 by JDBC; approve; charge = quote(today … contract end).
  - `deactivatedIncomeLeafRefusesApproval` (Review Focus 3): set bay leaf, deactivate it; approve throws `booking.incomeAccount`; booking still `PENDING`; no journal.
  - `feeChangedWhilePendingChargesTheQuote` (Review Focus 4): request at 300/month, change bay fee to 500, approve; Cr 600.00.
  - `noJournalInsideALockedPeriod`: lock last month, approve; every booking journal `entry_date >= today`.
  - `earlyReleaseKeepsTheFee`: approve, then `release` it before its end date; charge still `APPROVED`, no new journal for it.

- [ ] **Step 2: Run, expect FAIL** `./gradlew test -PincludeTags=golden --tests '*GoldenBookingJournalVoucherIT'`

- [ ] **Step 3: Implement.**
  - `incomeAccountFor`: the bay's/amenity's `incomeAccountId` (load the `Account`, `IncomeLeafRule.require(account, propertyId)`); else parking → `accountResolver.resolve(PARKING_INCOME, propertyId)`; amenity → `resolveOrNull(AMENITY_INCOME, propertyId)`, else `resolve(OTHER_INCOME, propertyId)`. Use `resolveOrNull`, never `resolve` inside try/catch (see `AccountResolver` javadoc).
  - `chargeOnApproval` passes it to the new `proposeFromSource` overload. Description: `"Booking fee - " + what + " " + start + (end != null && !end.equals(start) ? " to " + end : "")`.
  - `BookingService.approve`: remember whether it set `preferredDate`/`preferredEndDate`; if so, `booking.setFeeAmount(BookingFeeService.quote(spot, booking))` before `chargeOnApproval`.

- [ ] **Step 4: Run** `./gradlew test -PincludeTags=golden` and `./gradlew test --tests '*Booking*' --tests '*Penalty*' --tests '*Notification*'`. Expected: PASS. Existing golden totals that included booking fees as `PEN`: update only the doc-type assertion, not amounts; call out each changed assertion in the commit body.

- [ ] **Step 5: Commit** `feat(bookings): booking fees post as journal vouchers to parking or amenity income`

---

### Task 4: Server quote endpoint and the clients

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/BookingController.java` (new `GET /bookings/quote`)
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/BookingQuoteDTO.java` — `record BookingQuoteDTO(String feeType, BigDecimal unitAmount, BigDecimal net, BigDecimal vat, BigDecimal total)`
- Modify: `web/src/lib/api/facilities.ts` (`fetchBookingQuote`), `web/src/types/facility.ts` (`BookingFeeType` adds `"PER_DAY" | "PER_MONTH"`; `incomeAccountId?: string | null` on spot and amenity DTOs)
- Modify: `web/src/app/[locale]/dashboard/renter-portal/facilities/page.tsx` (use the quote endpoint, drop the local estimate)
- Modify: `web/src/app/[locale]/dashboard/properties/[id]/_components/ParkingTab.tsx`, `AmenitiesTab.tsx` (fee type options per the Global Constraints; `AccountPicker accountType="INCOME"` filtered by property, optional)
- Modify: `web/messages/en.json`, `web/messages/ar.json` (`fee.PER_DAY`, `fee.PER_MONTH`, `incomeAccount`, `incomeAccountHint`)
- Modify: `mobile/packages/rentaxis_core/lib/api/services/facility_service.dart` (`quote(...)`), `mobile/apps/renter/lib/screens/facilities/facilities_screen.dart` (fee label :33 for all five types; `_RequestSheet` shows the server quote, drops the estimate at :853)
- Test: `backend/.../api/BookingQuoteControllerIT.java`; `web/.../_components/__tests__/ParkingTabFee.test.tsx`; `web/.../renter-portal/facilities/__tests__/page.test.tsx`; `mobile/apps/renter/test/facilities_screen_test.dart`

**Interfaces:**
- Consumes: Task 1 `quote(...)`, Task 2 DTO field `incomeAccountId`.
- Produces: `GET /api/v1/bookings/quote?resourceType=&resourceId=&start=&end=&from=&to=` → `BookingQuoteDTO`. Roles: RENTER (only resources visible to their unit, as `/facilities/my`), SUPER_ADMIN, TENANT_ADMIN, PROPERTY_MANAGER. VAT is 5% only when the renter's live lease is VAT-registered, as on approval; staff calls without a renter get `vat` computed as 5%.

- [ ] **Step 1: Write failing tests:**
  - IT: parking `PER_MONTH` 300, 1 Dec–31 Jan → `net 600.00`, `total 630.00`; a renter asking about another property's bay → 404.
  - `ParkingTabFee.test.tsx`: the select offers `FREE, PER_BOOKING, PER_DAY, PER_MONTH` and not `PER_HOUR`; saving sends `incomeAccountId`.
  - Renter portal test: the shown fee comes from the mocked quote call (e.g. `630.00`), not from the bay's `feeAmount`.
  - Flutter: `_RequestSheet` shows `"630.00"` from a fake `quote`; fee label for `PER_MONTH` is `"per month"` / `"لكل شهر"`.
- [ ] **Step 2: Run, expect FAIL** (`./gradlew test --tests '*BookingQuoteControllerIT'`, `cd web && npx vitest run ParkingTabFee page.test`, `cd mobile/apps/renter && flutter test test/facilities_screen_test.dart`).
- [ ] **Step 3: Implement.** Debounce the quote call on date changes (300 ms) in web and mobile.
- [ ] **Step 4: Run all three suites.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(bookings): server booking quote; per-day/per-month and income account in web and mobile`

---

### Task 5: Staff booking on behalf of a renter

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/BookingController.java` (`POST /bookings/on-behalf`)
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/BookingOnBehalfRequest.java` — `record BookingOnBehalfRequest(@Valid @NotNull BookingCreateRequest booking, @NotNull UUID renterUserId)`
- Modify: `core/service/BookingService.java` — `BookingRequest createOnBehalf(UUID tenantId, UUID staffUserId, UUID renterUserId, Unit unit, BookingCreateRequest req)`: `create(...)` then `approve(...)` in one transaction
- Modify: `web/src/app/[locale]/dashboard/bookings/page.tsx` + new `_components/NewBookingDialog.tsx` (property → unit → renter from the unit's live lease → resource → dates; shows the server quote)
- Test: `backend/.../core/service/BookingOnBehalfIT.java`; `web/.../bookings/__tests__/NewBookingDialog.test.tsx`

**Interfaces:**
- Consumes: Task 3 approval path, Task 4 `fetchBookingQuote`.
- Produces: `POST /api/v1/bookings/on-behalf` → 201 `BookingRequestDTO` with status `APPROVED`. Roles: SUPER_ADMIN, TENANT_ADMIN, PROPERTY_MANAGER (property access as `/bookings` list).

- [ ] **Step 1: Write failing tests:**
  - `staffBookingIsApprovedAndChargedAtOnce`: banquet hall `PER_DAY` 1000, one day → booking `APPROVED`, one `JV` Cr amenity leaf 1000.00.
  - `renterNotOnTheUnitIsRefused` (Review Focus 5): another renter's user id → `booking.noLease`; no booking row, no charge.
  - `overlappingBayIsRefused`: existing approved overlap → `booking.spotTaken`; nothing created.
  - Web: dialog submits `{booking, renterUserId}` and closes on 201.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.** The renter must hold a live lease on `unit` (same lookup as `BookingFeeService.chargeOnApproval`) — check before `create`.
- [ ] **Step 4: Run** backend and web tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat(bookings): staff can book an amenity or bay for a renter`

---

### Task 6: Reconciliation check and full gate

**Files:**
- Modify: `backend/src/test/java/com/datagami/rentaxis/golden/GoldenBookingJournalVoucherIT.java`

- [ ] **Step 1: Add `bookingsChargesAndJournalsReconcile`:** after the scenarios above in one tenant, assert with SQL that
  - approved, not-cancelled bookings with `fee_amount > 0` = `APPROVED` `BOOKING_FEE` charges (count and sum of `amount`);
  - those charges' journals' income credits sum to the same total;
  - every charge's receivable debits equal its collection row amount;
  - cancelled bookings' charges net to 0 on income and receivable.
- [ ] **Step 2: Run the gate:**
  - `cd backend && ./gradlew test -PincludeTags=golden` → PASS
  - `./gradlew test -PexcludeTags=golden` → PASS
  - `cd web && npx vitest run` → PASS
  - `cd mobile/apps/renter && flutter test` → PASS
- [ ] **Step 3: Commit** `test(golden): booking fees reconcile with charges and journals`
