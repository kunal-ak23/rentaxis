# Lease Agreement Template Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Upgrade the existing lease contract PDF generation to match the client-provided 6-page bilingual format (`docs/plans/2026-04-30-lease-agreement-template-design.md`), with new lease/payment-schedule/landlord fields and per-component VAT support.

**Architecture:** Extend existing `ContractGenerationService` + `contract-template.html` (do not replace). Add columns to `lease`, `payment_schedules`, and `landlord_org` via Liquibase 45. Inline two new HTML partials (English + Arabic terms) at render time. Per-component VAT flags on the lease feed into existing `FinancialTransaction.vatApplicable/vatRate` so the existing VAT return picks them up.

**Tech Stack:** Spring Boot 4 + JPA + Liquibase + OpenHtmlToPdf 1.1.37 (existing); Next.js 16 + Tailwind (web); Flutter + Riverpod (mobile manager app).

**Source design:** `docs/plans/2026-04-30-lease-agreement-template-design.md`

---

## Milestone 0 — Setup

### Task 0.1: Read the design doc and confirm understanding

**Files:**
- Read: `docs/plans/2026-04-30-lease-agreement-template-design.md`
- Read: `docs/plans/2026-04-30-lease-agreement-template-plan.md` (this file)
- Reference (client format): the original PDF the client shared (`SR2-603_001.pdf`) — not in repo

**Step 1:** Read the design doc top-to-bottom. The plan tasks below assume you understand:
- Sections 1–4 of the rendered PDF and which DB fields back each cell
- The 53 numbered T&Cs are hardcoded in the template
- VAT toggles default `false` for residential, `true` for commercial properties
- Booking deposit is a special PaymentSchedule row (`isBookingDeposit=true`)
- The first installment can be "bundled" with admin/SD/parking → `purposeLabel` suffix `/ADMIN/SD/REMOTE`

**Step 2:** Open the existing template at `backend/src/main/resources/templates/contract-template.html` and note that the existing `ContractGenerationService` does plain `String.replace("{{KEY}}", value)` substitution (no Thymeleaf engine).

**No commit at this task.**

---

## Milestone 1 — Backend schema (Liquibase 45)

### Task 1.1: Create Liquibase changeset 45

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/45-lease-agreement-fields.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (add `include` for the new file at the bottom)

**Step 1: Create the changeset file**

```yaml
databaseChangeLog:
  - changeSet:
      id: 45-lease-agreement-fields
      author: rentaxis
      changes:
        - addColumn:
            tableName: leases
            columns:
              - column:
                  name: contract_number
                  type: bigint
                  constraints:
                    nullable: true
              - column:
                  name: agreement_date
                  type: date
                  constraints:
                    nullable: true
              - column:
                  name: admin_fee
                  type: decimal(12,2)
                  defaultValueNumeric: 0
                  constraints:
                    nullable: false
              - column:
                  name: parking_remote_fee
                  type: decimal(12,2)
                  defaultValueNumeric: 0
                  constraints:
                    nullable: false
              - column:
                  name: rent_vat_applicable
                  type: boolean
                  defaultValueBoolean: false
                  constraints:
                    nullable: false
              - column:
                  name: admin_fee_vat_applicable
                  type: boolean
                  defaultValueBoolean: false
                  constraints:
                    nullable: false
              - column:
                  name: security_deposit_vat_applicable
                  type: boolean
                  defaultValueBoolean: false
                  constraints:
                    nullable: false
              - column:
                  name: parking_remote_vat_applicable
                  type: boolean
                  defaultValueBoolean: false
                  constraints:
                    nullable: false
      comment: "Add contract metadata, charge fields, and per-component VAT flags to leases."

  - changeSet:
      id: 45-lease-contract-number-unique
      author: rentaxis
      changes:
        - createIndex:
            indexName: uq_lease_contract_number_per_org
            tableName: leases
            unique: true
            columns:
              - column:
                  name: tenant_id
              - column:
                  name: contract_number
      comment: "Ensure contract_number is unique per tenant_id (which equals landlord_org_id in this multi-tenant model)."

  - changeSet:
      id: 45-payment-schedule-purpose-and-booking
      author: rentaxis
      changes:
        - addColumn:
            tableName: payment_schedules
            columns:
              - column:
                  name: purpose_label
                  type: varchar(120)
                  constraints:
                    nullable: true
              - column:
                  name: is_booking_deposit
                  type: boolean
                  defaultValueBoolean: false
                  constraints:
                    nullable: false
      comment: "Add purpose_label and is_booking_deposit to payment_schedules for lease agreement Section 4."

  - changeSet:
      id: 45-landlord-org-phone-and-stamp
      author: rentaxis
      changes:
        - addColumn:
            tableName: landlord_org
            columns:
              - column:
                  name: phone
                  type: varchar(40)
                  constraints:
                    nullable: true
              - column:
                  name: stamp_image_url
                  type: text
                  constraints:
                    nullable: true
      comment: "Add phone and stamp_image_url to landlord_org for lease agreement header and signature stamp."
```

**Note on uniqueness:** in this codebase `tenant_id` on `BaseTenantEntity` corresponds to `LandlordOrg.id` (the multi-tenant key). The unique index uses `(tenant_id, contract_number)` — confirm this assumption by reading `BaseTenantEntity` and one existing changeset (e.g., `01-init-landlord-org.yaml`) before committing. If the codebase has a separate `landlord_org_id` foreign key on `leases`, swap the index column accordingly.

**Step 2: Add the include to the master changelog**

Open `backend/src/main/resources/db/changelog/db.changelog-master.yaml` and append:

```yaml
  - include:
      file: db/changelog/changesets/45-lease-agreement-fields.yaml
```

**Step 3: Apply migration to local DB**

Run: `cd backend && ./gradlew update`
Expected: Liquibase reports 4 changesets applied. No errors.

**Step 4: Verify columns exist**

Run: `psql -U postgres -d rentaxis -c "\d leases" | grep -E "contract_number|agreement_date|admin_fee|parking_remote_fee|.*_vat_applicable"`
Expected: 8 rows (one per new column).

Run: `psql -U postgres -d rentaxis -c "\d payment_schedules" | grep -E "purpose_label|is_booking_deposit"`
Expected: 2 rows.

Run: `psql -U postgres -d rentaxis -c "\d landlord_org" | grep -E "phone|stamp_image_url"`
Expected: 2 rows.

**Step 5: Commit**

```bash
git add backend/src/main/resources/db/changelog/changesets/45-lease-agreement-fields.yaml \
        backend/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat(db): add lease agreement fields to leases, payment_schedules, landlord_org"
```

---

## Milestone 2 — Backend entity changes

### Task 2.1: Extend `Lease` entity

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/Lease.java`

**Step 1:** Add the eight new fields with annotations matching their column types. Place them after `paymentReferenceNumber`, before `version`:

```java
    @Column(name = "contract_number")
    private Long contractNumber;

    @Column(name = "agreement_date")
    private java.time.LocalDate agreementDate;

    @Column(name = "admin_fee", nullable = false)
    private BigDecimal adminFee = BigDecimal.ZERO;

    @Column(name = "parking_remote_fee", nullable = false)
    private BigDecimal parkingRemoteFee = BigDecimal.ZERO;

    @Column(name = "rent_vat_applicable", nullable = false)
    private boolean rentVatApplicable = false;

    @Column(name = "admin_fee_vat_applicable", nullable = false)
    private boolean adminFeeVatApplicable = false;

    @Column(name = "security_deposit_vat_applicable", nullable = false)
    private boolean securityDepositVatApplicable = false;

    @Column(name = "parking_remote_vat_applicable", nullable = false)
    private boolean parkingRemoteVatApplicable = false;
```

**Step 2: Compile**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL. Lombok generates getters/setters for the new fields.

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/Lease.java
git commit -m "feat: add contract metadata and VAT fields to Lease entity"
```

### Task 2.2: Extend `PaymentSchedule` entity

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/PaymentSchedule.java`

**Step 1:** Add the two new fields after `paymentMethod`:

```java
    @Column(name = "purpose_label", length = 120)
    private String purposeLabel;

    @Column(name = "is_booking_deposit", nullable = false)
    private boolean isBookingDeposit = false;
```

**Step 2: Compile**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/PaymentSchedule.java
git commit -m "feat: add purposeLabel and isBookingDeposit to PaymentSchedule"
```

### Task 2.3: Extend `LandlordOrg` entity

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/LandlordOrg.java`

**Step 1:** Add the two new fields and matching getters/setters (this entity uses manual getters/setters, not Lombok). Place them after `logoUrl`:

```java
    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "stamp_image_url", columnDefinition = "text")
    private String stampImageUrl;

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getStampImageUrl() { return stampImageUrl; }
    public void setStampImageUrl(String stampImageUrl) { this.stampImageUrl = stampImageUrl; }
```

**Step 2: Compile**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/LandlordOrg.java
git commit -m "feat: add phone and stamp_image_url to LandlordOrg"
```

---

## Milestone 3 — `AmountInWordsUtil` (TDD)

### Task 3.1: Write failing test for `AmountInWordsUtil`

**Files:**
- Create: `backend/src/test/java/com/datagami/rentaxis/core/util/AmountInWordsUtilTest.java`

**Step 1: Write the test**

```java
package com.datagami.rentaxis.core.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmountInWordsUtilTest {

    @Test
    void zero() {
        assertThat(AmountInWordsUtil.toEnglishWords(BigDecimal.ZERO, "AED"))
                .isEqualTo("AED Zero Only");
    }

    @Test
    void one() {
        assertThat(AmountInWordsUtil.toEnglishWords(BigDecimal.ONE, "AED"))
                .isEqualTo("AED One Only");
    }

    @Test
    void nineteen() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("19"), "AED"))
                .isEqualTo("AED Nineteen Only");
    }

    @Test
    void twenty() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("20"), "AED"))
                .isEqualTo("AED Twenty Only");
    }

    @Test
    void ninetyNine() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("99"), "AED"))
                .isEqualTo("AED Ninety Nine Only");
    }

    @Test
    void oneHundred() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("100"), "AED"))
                .isEqualTo("AED One Hundred Only");
    }

    @Test
    void nineHundredNinetyNine() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("999"), "AED"))
                .isEqualTo("AED Nine Hundred Ninety Nine Only");
    }

    @Test
    void oneThousand() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("1000"), "AED"))
                .isEqualTo("AED One Thousand Only");
    }

    @Test
    void contractSampleNumber() {
        // The PDF sample shows "1751" as the contract number
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("1751"), "AED"))
                .isEqualTo("AED One Thousand Seven Hundred Fifty One Only");
    }

    @Test
    void contractSampleTotal() {
        // The PDF sample shows total 60,300 -> "Sixty Thousand Three Hundred"
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("60300"), "AED"))
                .isEqualTo("AED Sixty Thousand Three Hundred Only");
    }

    @Test
    void oneMillion() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("1000000"), "AED"))
                .isEqualTo("AED One Million Only");
    }

    @Test
    void largeNumber() {
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("999999999"), "AED"))
                .isEqualTo("AED Nine Hundred Ninety Nine Million Nine Hundred Ninety Nine Thousand Nine Hundred Ninety Nine Only");
    }

    @Test
    void roundsFractionalPartToWholeUnit() {
        // Sample PDF rounds fils away — only whole AED in words
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("100.49"), "AED"))
                .isEqualTo("AED One Hundred Only");
        assertThat(AmountInWordsUtil.toEnglishWords(new BigDecimal("100.50"), "AED"))
                .isEqualTo("AED One Hundred One Only");
    }

    @Test
    void rejectsNegative() {
        assertThatThrownBy(() -> AmountInWordsUtil.toEnglishWords(new BigDecimal("-1"), "AED"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullAmount() {
        assertThatThrownBy(() -> AmountInWordsUtil.toEnglishWords(null, "AED"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests "com.datagami.rentaxis.core.util.AmountInWordsUtilTest"`
Expected: FAIL — "cannot find symbol class AmountInWordsUtil" or similar.

### Task 3.2: Implement `AmountInWordsUtil`

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/util/AmountInWordsUtil.java`

**Step 1: Write the minimal implementation**

```java
package com.datagami.rentaxis.core.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class AmountInWordsUtil {

    private static final String[] BELOW_TWENTY = {
            "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
            "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen",
            "Sixteen", "Seventeen", "Eighteen", "Nineteen"
    };

    private static final String[] TENS = {
            "", "", "Twenty", "Thirty", "Forty", "Fifty",
            "Sixty", "Seventy", "Eighty", "Ninety"
    };

    private AmountInWordsUtil() {}

    public static String toEnglishWords(BigDecimal amount, String currencyCode) {
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        long whole = amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
        String words = numberToWords(whole);
        return currencyCode + " " + words + " Only";
    }

    private static String numberToWords(long n) {
        if (n == 0) return "Zero";
        StringBuilder sb = new StringBuilder();
        appendChunk(sb, n / 1_000_000_000L, "Billion");
        n %= 1_000_000_000L;
        appendChunk(sb, n / 1_000_000L, "Million");
        n %= 1_000_000L;
        appendChunk(sb, n / 1_000L, "Thousand");
        n %= 1_000L;
        appendBelowThousand(sb, (int) n);
        return sb.toString().trim();
    }

    private static void appendChunk(StringBuilder sb, long count, String unit) {
        if (count == 0) return;
        appendBelowThousand(sb, (int) count);
        sb.append(unit).append(' ');
    }

    private static void appendBelowThousand(StringBuilder sb, int n) {
        if (n == 0) return;
        if (n >= 100) {
            sb.append(BELOW_TWENTY[n / 100]).append(" Hundred ");
            n %= 100;
        }
        if (n >= 20) {
            sb.append(TENS[n / 10]).append(' ');
            n %= 10;
        }
        if (n > 0 && n < 20) {
            sb.append(BELOW_TWENTY[n]).append(' ');
        }
    }
}
```

**Step 2: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests "com.datagami.rentaxis.core.util.AmountInWordsUtilTest"`
Expected: All 14 tests PASS.

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/util/AmountInWordsUtil.java \
        backend/src/test/java/com/datagami/rentaxis/core/util/AmountInWordsUtilTest.java
git commit -m "feat: add AmountInWordsUtil for lease agreement amount-in-words line"
```

---

## Milestone 4 — DTO updates

### Task 4.1: Extend `CreateLeaseDTO`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/dto/CreateLeaseDTO.java`

**Step 1:** Add fields after `paymentReferenceNumber`:

```java
    private LocalDate agreementDate;

    @Min(0)
    private BigDecimal adminFee;

    @Min(0)
    private BigDecimal parkingRemoteFee;

    private Boolean rentVatApplicable;

    private Boolean adminFeeVatApplicable;

    private Boolean securityDepositVatApplicable;

    private Boolean parkingRemoteVatApplicable;

    private BookingDepositDTO bookingDeposit;

    @lombok.Data
    public static class BookingDepositDTO {
        @NotNull
        @Min(0)
        private BigDecimal amount;
        private String chequeNumber;
        private LocalDate chequeDate;
        private String bankName;
    }
```

**Step 2: Compile**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/dto/CreateLeaseDTO.java
git commit -m "feat: add new lease fields to CreateLeaseDTO"
```

### Task 4.2: Extend `LeaseResponseDTO` (and any matching response DTO)

**Files:**
- Locate: search `LeaseResponseDTO` / `LeaseDTO` in `backend/src/main/java/com/datagami/rentaxis/api/dto/`

**Step 1:** Add these fields to whichever DTO the controller returns from `GET /api/v1/leases/{id}`:

```java
    private Long contractNumber;
    private LocalDate agreementDate;
    private BigDecimal adminFee;
    private BigDecimal parkingRemoteFee;
    private Boolean rentVatApplicable;
    private Boolean adminFeeVatApplicable;
    private Boolean securityDepositVatApplicable;
    private Boolean parkingRemoteVatApplicable;
```

**Step 2:** Update the mapper (look for `mapToDTO(Lease)` or `LeaseMapper`) to populate these.

**Step 3: Compile**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/dto/<file>.java <mapper-files>
git commit -m "feat: expose new lease fields in response DTO"
```

### Task 4.3: Extend `PaymentScheduleResponseDTO`

**Files:**
- Locate: `PaymentScheduleDTO` / `PaymentScheduleResponseDTO` in `api/dto/`

**Step 1:** Add `purposeLabel`, `isBookingDeposit` fields. Update mapper.

**Step 2: Compile + commit**

```bash
git add <files>
git commit -m "feat: expose purposeLabel and isBookingDeposit in PaymentSchedule response"
```

### Task 4.4: Extend `LandlordOrgDTO`

**Files:**
- Locate: `LandlordOrgDTO` in `api/dto/`

**Step 1:** Add `phone`, `stampImageUrl`. Update mapper.

**Step 2: Compile + commit**

```bash
git add <files>
git commit -m "feat: expose phone and stampImageUrl in LandlordOrgDTO"
```

---

## Milestone 5 — `LeaseService` updates

### Task 5.1: Apply VAT defaults from property type at lease creation

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/LeaseService.java`

**Step 1:** Find `createLease(CreateLeaseDTO)` (or equivalent). Before saving, apply the new fields:

```java
// Map new fields from DTO to entity
lease.setAgreementDate(dto.getAgreementDate());
lease.setAdminFee(dto.getAdminFee() != null ? dto.getAdminFee() : BigDecimal.ZERO);
lease.setParkingRemoteFee(dto.getParkingRemoteFee() != null ? dto.getParkingRemoteFee() : BigDecimal.ZERO);

// VAT defaults: COMMERCIAL property -> all true; otherwise -> false
boolean commercialDefault = unit.getProperty().getType() != null
        && "COMMERCIAL".equalsIgnoreCase(unit.getProperty().getType().name());
lease.setRentVatApplicable(dto.getRentVatApplicable() != null ? dto.getRentVatApplicable() : commercialDefault);
lease.setAdminFeeVatApplicable(dto.getAdminFeeVatApplicable() != null ? dto.getAdminFeeVatApplicable() : commercialDefault);
lease.setSecurityDepositVatApplicable(dto.getSecurityDepositVatApplicable() != null ? dto.getSecurityDepositVatApplicable() : commercialDefault);
lease.setParkingRemoteVatApplicable(dto.getParkingRemoteVatApplicable() != null ? dto.getParkingRemoteVatApplicable() : commercialDefault);
```

**Step 2:** If `Property.getType()` returns an enum without a `COMMERCIAL` constant, check whether it has `RESIDENTIAL`/`COMMERCIAL` values; otherwise read `Property.java` and adjust the comparison. (This is a small piece of integration work — confirm the enum once and adjust.)

**Step 3: Run any existing LeaseService tests**

Run: `cd backend && ./gradlew test --tests "*LeaseService*"`
Expected: existing tests still PASS.

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/LeaseService.java
git commit -m "feat: apply VAT defaults from property type when creating lease"
```

### Task 5.2: Auto-populate `purposeLabel` on PaymentSchedule rows

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/LeaseService.java` (or wherever `PaymentSchedule` rows are generated — search `new PaymentSchedule()` in the codebase first)

**Step 1:** Find the loop that creates payment schedule rows from `paymentTerms`. After setting `installmentNumber`, set `purposeLabel`:

```java
String label = "RENT - " + ordinalSuffix(installmentNumber) + " INSTALLMENT";
boolean isFirstInstallment = installmentNumber == 1;
boolean hasBundledCharges = isFirstInstallment
        && (lease.getAdminFee().signum() > 0
            || lease.getDepositAmount().signum() > 0
            || lease.getParkingRemoteFee().signum() > 0);
if (hasBundledCharges) {
    label += "/ADMIN/SD/REMOTE";
}
schedule.setPurposeLabel(label);
```

Add helper:

```java
private static String ordinalSuffix(int n) {
    if (n % 100 >= 11 && n % 100 <= 13) return n + "TH";
    return switch (n % 10) {
        case 1 -> n + "ST";
        case 2 -> n + "ND";
        case 3 -> n + "RD";
        default -> n + "TH";
    };
}
```

**Step 2: Compile**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/LeaseService.java
git commit -m "feat: auto-populate PaymentSchedule.purposeLabel from installment number"
```

### Task 5.3: Add `addBookingDeposit` method

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/LeaseService.java`

**Step 1:** Add a method:

```java
@Transactional
public PaymentSchedule addBookingDeposit(UUID leaseId, BigDecimal amount,
                                         String chequeNumber, LocalDate chequeDate, String bankName) {
    Lease lease = leaseRepository.findById(leaseId)
            .orElseThrow(() -> new NotFoundException("Lease not found"));
    PaymentSchedule ps = new PaymentSchedule();
    ps.setLease(lease);
    ps.setUnit(lease.getUnit());
    ps.setProperty(lease.getUnit().getProperty());
    ps.setInstallmentNumber(0);
    ps.setDueDate(chequeDate != null ? chequeDate : LocalDate.now());
    ps.setChequeDate(chequeDate);
    ps.setChequeNumber(chequeNumber);
    ps.setBankName(bankName);
    ps.setAmount(amount);
    ps.setPurposeLabel("BOOKING RECEIVED");
    ps.setBookingDeposit(true); // setter generated by Lombok from `boolean isBookingDeposit`
    return paymentScheduleRepository.save(ps);
}
```

**Step 2:** Call `addBookingDeposit` from `createLease(...)` if `dto.getBookingDeposit() != null`.

**Step 3: Compile**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/LeaseService.java
git commit -m "feat: add booking deposit row to PaymentSchedule on lease creation"
```

---

## Milestone 6 — Contract terms HTML partials

### Task 6.1: Create English terms partial

**Files:**
- Create: `backend/src/main/resources/templates/contract-terms-en.html`

**Step 1:** Create the file with all 53 numbered terms verbatim from the client's PDF (pages 2–6). Each term wrapped in `<li value="N">`. Skeleton:

```html
<ol class="terms-list">
  <li value="1">The Tenant shall be solely liable for the payment of VAT to the Landlord/Lessor and/or any other taxes which may be levied or payable in connection with the Tenancy Contract. During the validity of the Contract and in the event of any changes in the value added tax due either by increase or decrease as per applicable laws, the Tenant shall compensate the Landlord/Lessor for any amounts incurred as a result.</li>
  <li value="2">The Tenant may use the premises only as a place of residence and strictly for family use only.</li>
  <!-- ... terms 3-53 verbatim from the PDF ... -->
  <li value="53">The tenant acknowledges that it has signed this contract personally and /or by a legally authorized signatory, under this clause the tenant unconditionally releases the landlord's / lessor's responsibility and bears legal liabilities on full-basis if proven otherwise.</li>
</ol>
```

**Step 2:** Carefully transcribe each of the 53 terms. Source: pages 2, 3, 4, 5, 6 of `SR2-603_001.pdf`. Cross-check spelling and numbering against the PDF.

**Step 3: Commit**

```bash
git add backend/src/main/resources/templates/contract-terms-en.html
git commit -m "feat: add English additional terms partial for lease agreement"
```

### Task 6.2: Create Arabic terms partial

**Files:**
- Create: `backend/src/main/resources/templates/contract-terms-ar.html`

**Step 1:** Create the same 53 numbered terms in Arabic (right side of PDF pages 2–6). Each term wrapped in `<li value="N">` inside `<ol class="terms-list" dir="rtl">`.

**Step 2:** Transcribe carefully — Arabic numerals can use either Hindi-Arabic (٠١٢٣) or Western (0123). Match the PDF exactly.

**Step 3: Commit**

```bash
git add backend/src/main/resources/templates/contract-terms-ar.html
git commit -m "feat: add Arabic additional terms partial for lease agreement"
```

---

## Milestone 7 — Rewrite contract template

### Task 7.1: Rewrite `contract-template.html` to match client format

**Files:**
- Modify: `backend/src/main/resources/templates/contract-template.html` (full rewrite)

**Step 1:** Replace the entire file with a new template that:

- Uses `@page` rules with margins matching the sample
- Has a header band repeated on every page (CSS `position: running()` with `@top-center`, OR rendered explicitly per page since OpenHtmlToPdf supports `running` only partially)
- Page 1: header + title + Contract No / Agreement Date row + Sections 1–4 + Amount-in-words row + signature block
- Page 2 starts with `<div style="page-break-before: always;">` → ADDITIONAL TERMS header → two-column grid `<div class="terms-grid"><div class="col-en">{{TERMS_EN}}</div><div class="col-ar">{{TERMS_AR}}</div></div>` → signature block at bottom
- Subsequent page breaks at the natural CSS overflow points (the layout will spill). To match the sample's term breaks (15, 26, 36, 49, 53), we don't need page-break rules per term — the CSS columns will flow.

Key placeholders the template uses (the service substitutes these in the next milestone):

```
{{LANDLORD_NAME}}
{{LANDLORD_ADDRESS}}
{{LANDLORD_PHONE}}
{{CONTRACT_NUMBER}}
{{AGREEMENT_DATE}}
{{BUILDING_NAME}}
{{TENANT_NAME}}
{{TENANT_EMAIL}}
{{TENANT_PHONE}}
{{LEASE_START_DATE}}
{{LEASE_END_DATE}}
{{FLAT_NUMBER}}
{{SECTION_3_ROWS}}     <!-- pre-rendered <tr>...</tr> rows for Section 3 -->
{{SECTION_3_TOTAL}}    <!-- pre-rendered total row -->
{{SECTION_4_ROWS}}     <!-- pre-rendered <tr>...</tr> rows for Section 4 -->
{{AMOUNT_IN_WORDS}}
{{GRAND_TOTAL}}
{{STAMP_IMG_OR_BLANK}} <!-- <img src="..."> or empty bordered div -->
{{TERMS_EN}}
{{TERMS_AR}}
```

Use `<table>` rather than CSS grid for tables (`detail-table`, `payment-table`) — OpenHtmlToPdf renders tables more reliably.

Keep the existing Noto Sans + Noto Sans Arabic font registration (handled by the service in `renderPdf`).

**Step 2:** Render once via the existing `POST /api/v1/leases/{id}/generate-contract` (using a draft lease) to confirm the template parses and produces a valid PDF — even if data is partially missing at this stage.

Run (after restarting backend): `curl -X POST http://localhost:8080/api/v1/leases/{leaseId}/generate-contract -H "Authorization: Bearer ..."`
Expected: 200 OK, document URL returned. Open the PDF — layout looks right (header, four sections, signature block).

**Step 3: Commit**

```bash
git add backend/src/main/resources/templates/contract-template.html
git commit -m "feat: rewrite lease agreement template to match client format"
```

---

## Milestone 8 — `ContractGenerationService` rewrite (TDD)

### Task 8.1: Write failing test for Section 3 rendering

**Files:**
- Create or modify: `backend/src/test/java/com/datagami/rentaxis/core/service/ContractGenerationServiceTest.java`

**Step 1: Write a test that builds a lease, calls a new helper `buildSection3Rows(Lease)`, and asserts on the resulting HTML**

```java
@Test
void section3HidesZeroAmountRows() {
    Lease lease = leaseFixture()
            .rentAmount(new BigDecimal("55000"))
            .adminFee(new BigDecimal("2000"))
            .depositAmount(new BigDecimal("3000"))
            .parkingRemoteFee(BigDecimal.ZERO)
            .rentVatApplicable(false)
            .adminFeeVatApplicable(false)
            .securityDepositVatApplicable(false)
            .parkingRemoteVatApplicable(false)
            .build();
    String html = service.buildSection3Rows(lease);
    assertThat(html).contains("Rent").contains("55,000.00").contains("Exempt");
    assertThat(html).contains("Admin Fee").contains("2,000.00");
    assertThat(html).contains("Security Deposit").contains("3,000.00");
    assertThat(html).doesNotContain("Parking Remote");
}

@Test
void section3RendersVatAt5PercentWhenApplicable() {
    Lease lease = leaseFixture()
            .rentAmount(new BigDecimal("55000"))
            .rentVatApplicable(true)
            .build();
    String html = service.buildSection3Rows(lease);
    assertThat(html).contains("5%").contains("2,750.00").contains("57,750.00");
}
```

**Step 2: Run to verify failure**

Run: `cd backend && ./gradlew test --tests "ContractGenerationServiceTest.section3*"`
Expected: FAIL — `buildSection3Rows` not defined.

### Task 8.2: Implement `buildSection3Rows`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/ContractGenerationService.java`

**Step 1:** Add a method that builds Section 3 row HTML:

```java
private static final BigDecimal VAT_RATE = new BigDecimal("0.05");
private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

String buildSection3Rows(Lease lease) {
    StringBuilder sb = new StringBuilder();
    int sno = 1;
    sno = appendChargeRow(sb, sno, "Rent", lease.getRentAmount(), lease.isRentVatApplicable());
    sno = appendChargeRow(sb, sno, "Admin Fee", lease.getAdminFee(), lease.isAdminFeeVatApplicable());
    sno = appendChargeRow(sb, sno, "Security Deposit", lease.getDepositAmount(), lease.isSecurityDepositVatApplicable());
    sno = appendChargeRow(sb, sno, "Parking Remote", lease.getParkingRemoteFee(), lease.isParkingRemoteVatApplicable());
    return sb.toString();
}

private int appendChargeRow(StringBuilder sb, int sno, String label, BigDecimal amount, boolean vatApplicable) {
    if (amount == null || amount.signum() == 0) return sno;
    BigDecimal vat = vatApplicable
            ? amount.multiply(VAT_RATE).setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(2);
    BigDecimal withVat = amount.add(vat).setScale(2, RoundingMode.HALF_UP);
    sb.append("<tr><td>").append(sno).append("</td>")
      .append("<td>").append(label).append("</td>")
      .append("<td>").append(formatAmount(amount)).append("</td>")
      .append("<td>").append(vatApplicable ? "5%" : "Exempt").append("</td>")
      .append("<td>").append(formatAmount(vat)).append("</td>")
      .append("<td>").append(formatAmount(withVat)).append("</td></tr>");
    return sno + 1;
}

private String formatAmount(BigDecimal amount) {
    return new java.text.DecimalFormat("#,##0.00").format(amount);
}
```

**Step 2: Run tests to verify pass**

Run: `cd backend && ./gradlew test --tests "ContractGenerationServiceTest.section3*"`
Expected: PASS.

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/ContractGenerationService.java \
        backend/src/test/java/com/datagami/rentaxis/core/service/ContractGenerationServiceTest.java
git commit -m "feat: add Section 3 row rendering with per-component VAT"
```

### Task 8.3: Test + implement `buildSection4Rows`

**Files:**
- Modify: `backend/src/test/java/com/datagami/rentaxis/core/service/ContractGenerationServiceTest.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/ContractGenerationService.java`

**Step 1: Test cases**

```java
@Test
void section4OrdersByChequeDateAscWithBookingLast() {
    PaymentSchedule p1 = ps(LocalDate.of(2026, 4, 18), "TT", "TRANSFER", new BigDecimal("18050"), "RENT - 1ST INSTALLMENT/ADMIN/SD/REMOTE", false);
    PaymentSchedule p2 = ps(LocalDate.of(2026, 7, 24), "000001", "ENBD", new BigDecimal("13750"), "RENT - 2ND INSTALLMENT", false);
    PaymentSchedule booking = ps(LocalDate.of(2026, 4, 14), "TT", "TRANSFER", new BigDecimal("1000"), "BOOKING RECEIVED", true);
    String html = service.buildSection4Rows(List.of(p2, booking, p1));
    int idxP1 = html.indexOf("18,050.00");
    int idxP2 = html.indexOf("13,750.00");
    int idxBooking = html.indexOf("BOOKING RECEIVED");
    assertThat(idxP1).isLessThan(idxP2);
    assertThat(idxBooking).isGreaterThan(idxP2); // booking last
}
```

**Step 2: Implementation**

```java
String buildSection4Rows(List<PaymentSchedule> schedules) {
    List<PaymentSchedule> ordered = new ArrayList<>(schedules);
    ordered.sort(Comparator
            .comparing(PaymentSchedule::isBookingDeposit) // false (regular) first, true (booking) last
            .thenComparing(p -> p.getChequeDate() != null ? p.getChequeDate() : LocalDate.MIN));
    StringBuilder sb = new StringBuilder();
    int sno = 1;
    for (PaymentSchedule p : ordered) {
        sb.append("<tr><td>").append(sno++).append("</td>")
          .append("<td>").append(safe(p.getChequeNumber())).append("</td>")
          .append("<td>").append(p.getChequeDate() != null ? formatDate(p.getChequeDate()) : "").append("</td>")
          .append("<td>").append(safe(p.getPurposeLabel())).append("</td>")
          .append("<td>").append(safe(p.getBankName())).append("</td>")
          .append("<td>").append(formatAmount(p.getAmount())).append("</td></tr>");
    }
    return sb.toString();
}

private String safe(String s) { return s == null ? "" : s; }

private String formatDate(LocalDate d) {
    return d.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy"));
}
```

**Step 3: Run tests, commit**

Run: `cd backend && ./gradlew test --tests "ContractGenerationServiceTest.section4*"`
Expected: PASS.

```bash
git add <files>
git commit -m "feat: add Section 4 row rendering with booking-last ordering"
```

### Task 8.4: Test + implement contract number assignment

**Files:**
- Modify: `backend/src/test/java/com/datagami/rentaxis/core/service/ContractGenerationServiceTest.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/ContractGenerationService.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/repository/LeaseRepository.java`

**Step 1: Add a repository query method**

```java
@Query("SELECT COALESCE(MAX(l.contractNumber), 0) FROM Lease l WHERE l.tenantId = :tenantId")
Long findMaxContractNumberForTenant(@Param("tenantId") UUID tenantId);
```

**Step 2: Test**

```java
@Test
void assignsSequentialContractNumberPerTenant() {
    UUID tenantId = UUID.randomUUID();
    Lease lease1 = newLease(tenantId);
    Lease lease2 = newLease(tenantId);
    service.assignContractNumberIfNull(lease1);
    service.assignContractNumberIfNull(lease2);
    assertThat(lease1.getContractNumber()).isEqualTo(1L);
    assertThat(lease2.getContractNumber()).isEqualTo(2L);
}

@Test
void doesNotReassignExistingContractNumber() {
    Lease lease = newLease(UUID.randomUUID());
    lease.setContractNumber(42L);
    service.assignContractNumberIfNull(lease);
    assertThat(lease.getContractNumber()).isEqualTo(42L);
}
```

**Step 3: Implementation**

```java
@Transactional
public void assignContractNumberIfNull(Lease lease) {
    if (lease.getContractNumber() != null) return;
    Long max = leaseRepository.findMaxContractNumberForTenant(lease.getTenantId());
    lease.setContractNumber(max + 1);
    // Persistence handled by caller (already in @Transactional generateContract).
}
```

The DB unique index handles concurrent inserts; if a `DataIntegrityViolationException` bubbles up in production, retry once. (For now, single-thread assumption is fine since contracts are generated by humans clicking a button.)

**Step 4: Run tests, commit**

Run: `cd backend && ./gradlew test --tests "ContractGenerationServiceTest*"`
Expected: PASS.

```bash
git add <files>
git commit -m "feat: assign sequential contract number per tenant on contract generation"
```

### Task 8.5: Wire all new substitutions into `generateContract`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/ContractGenerationService.java`

**Step 1:** Replace the body of `generateContract` to use new substitutions:

```java
// After loading lease, unit, property, renter:
LandlordOrg org = landlordOrgRepository.findById(lease.getTenantId()).orElseThrow();
List<PaymentSchedule> schedules = paymentScheduleRepository.findByLeaseId(leaseId);

assignContractNumberIfNull(lease);
if (lease.getAgreementDate() == null) {
    lease.setAgreementDate(LocalDate.now());
}
leaseRepository.save(lease);

BigDecimal grandTotal = computeGrandTotal(lease);
String amountInWords = AmountInWordsUtil.toEnglishWords(grandTotal, "AED");

String stampHtml = (org.getStampImageUrl() != null && !org.getStampImageUrl().isBlank())
        ? "<img src=\"" + org.getStampImageUrl() + "\" style=\"width:120px;height:auto;\"/>"
        : "<div style=\"width:120px;height:120px;border:1px dashed #ccc;\"></div>";

String termsEn = loadResource("templates/contract-terms-en.html");
String termsAr = loadResource("templates/contract-terms-ar.html");

String html = template
        .replace("{{LANDLORD_NAME}}", safe(org.getName()))
        .replace("{{LANDLORD_ADDRESS}}", safe(org.getAddress()))
        .replace("{{LANDLORD_PHONE}}", safe(org.getPhone()))
        .replace("{{CONTRACT_NUMBER}}", String.valueOf(lease.getContractNumber()))
        .replace("{{AGREEMENT_DATE}}", formatDate(lease.getAgreementDate()))
        .replace("{{BUILDING_NAME}}", safe(property.getNameEn()))
        .replace("{{TENANT_NAME}}", safe(renter.getNameEn()))
        .replace("{{TENANT_EMAIL}}", renter.getEmail() != null ? renter.getEmail() : "")
        .replace("{{TENANT_PHONE}}", renter.getPhone() != null ? renter.getPhone() : "")
        .replace("{{LEASE_START_DATE}}", formatDate(lease.getStartDate()))
        .replace("{{LEASE_END_DATE}}", formatDate(lease.getEndDate()))
        .replace("{{FLAT_NUMBER}}", safe(unit.getUnitNumber()))
        .replace("{{SECTION_3_ROWS}}", buildSection3Rows(lease))
        .replace("{{SECTION_3_TOTAL}}", buildSection3TotalRow(lease))
        .replace("{{SECTION_4_ROWS}}", buildSection4Rows(schedules))
        .replace("{{AMOUNT_IN_WORDS}}", amountInWords)
        .replace("{{GRAND_TOTAL}}", formatAmount(grandTotal))
        .replace("{{STAMP_IMG_OR_BLANK}}", stampHtml)
        .replace("{{TERMS_EN}}", termsEn)
        .replace("{{TERMS_AR}}", termsAr);
```

Add helpers `loadResource`, `computeGrandTotal`, `buildSection3TotalRow`. Inject `LandlordOrgRepository` and `PaymentScheduleRepository` into the constructor.

**Step 2: Compile + run all backend tests**

Run: `cd backend && ./gradlew test`
Expected: existing tests still PASS (don't break anything).

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/ContractGenerationService.java
git commit -m "feat: render full client-format lease agreement from new template"
```

### Task 8.6: Add `/preview` endpoint (no persistence)

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/ContractGenerationService.java` — extract a `renderHtml(Lease)` private method that doesn't persist anything, then add a `previewContract(UUID leaseId)` method that returns `byte[]` of the PDF using a placeholder `"DRAFT"` for the contract number.
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/LeaseController.java` — add:

```java
@PostMapping("/{id}/generate-contract/preview")
public ResponseEntity<byte[]> previewContract(@PathVariable UUID id) {
    byte[] pdf = contractGenerationService.previewContract(id);
    return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"lease-preview.pdf\"")
            .body(pdf);
}
```

**Step 2: Manual smoke test**

Run: `curl -X POST -o preview.pdf http://localhost:8080/api/v1/leases/{leaseId}/generate-contract/preview -H "Authorization: Bearer ..."`
Expected: 200 OK, PDF saved. Open it — preview renders with `Contract No. : DRAFT`.

**Step 3: Commit**

```bash
git add <files>
git commit -m "feat: add lease contract preview endpoint (no persistence)"
```

---

## Milestone 9 — VAT integration with FinancialTransaction

### Task 9.1: Stamp `vatApplicable` + `vatRate` on lease-related transactions

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/FinancialTransactionService.java` (or whichever service creates `FinancialTransaction` rows for rent/admin/SD/parking)

**Step 1:** Find every place that creates a `FinancialTransaction` linked to a lease/payment-schedule. For each charge type, set the VAT fields from the matching lease flag:

```java
// example: when creating a transaction for a rent payment
boolean vatApplicable = lease.isRentVatApplicable();
tx.setVatApplicable(vatApplicable);
tx.setVatRate(vatApplicable ? new BigDecimal("5.00") : BigDecimal.ZERO);
if (vatApplicable) {
    BigDecimal net = amount; // amount is net (rent is quoted net of VAT)
    BigDecimal vat = net.multiply(new BigDecimal("0.05")).setScale(2, RoundingMode.HALF_UP);
    tx.setVatAmount(vat);
}
```

Repeat for admin fee / SD / parking transactions, reading the matching flag.

**Step 2:** Existing tests for `FinancialTransactionService` should still pass. Run: `cd backend && ./gradlew test --tests "*FinancialTransactionService*"`
Expected: PASS.

**Step 3: Commit**

```bash
git add <files>
git commit -m "feat: stamp VAT fields on lease-related transactions from lease toggles"
```

---

## Milestone 10 — LandlordOrg phone + stamp

### Task 10.1: Stamp upload endpoint

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/LandlordOrgController.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/LandlordOrgService.java`

**Step 1:** Mirror the existing logo upload endpoint (search the controller for `logo`). The new endpoint:

```java
@PostMapping("/stamp")
public ResponseEntity<LandlordOrgDTO> uploadStamp(@RequestParam("file") MultipartFile file) {
    return ResponseEntity.ok(landlordOrgService.uploadStamp(file));
}
```

**Step 2:** Implement `uploadStamp` in the service — use the same blob-upload helper as the logo. Save the URL to `LandlordOrg.stampImageUrl`.

**Step 3:** Add `phone` to whatever PATCH-style profile-update endpoint already exists.

**Step 4: Manual test**

Run: `curl -X POST -F "file=@stamp.png" http://localhost:8080/api/v1/landlord-org/stamp -H "Authorization: Bearer ..."`
Expected: 200 OK; LandlordOrg now has a `stampImageUrl`.

**Step 5: Commit**

```bash
git add <files>
git commit -m "feat: add LandlordOrg stamp upload endpoint and phone field"
```

---

## Milestone 11 — Web UI

### Task 11.1: Add new fields to lease create/edit form

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/[id]/page.tsx` (and the create page if separate)

**Step 1:** Add to the form:
- "Charges" section: existing rent input, new Admin Fee input, existing deposit input, new Parking Remote input. Each with a small "VAT 5%" toggle next to it.
- "Agreement Date" date picker (optional).
- Optional collapsible "Booking Deposit" subform: amount, cheque number, cheque date, bank.

**Step 2:** Add inline-editable "In Favour Of" labels to each row in the existing payment-schedule editor.

**Step 3:** Wire form state into the existing API call payload.

**Step 4: Run dev server and verify in browser**

Run: `cd web && npm run dev`. Open `http://localhost:3000/en/dashboard/leases/new`.
Manually verify:
- All new inputs render
- Property selection of a COMMERCIAL property auto-toggles all four VAT switches ON
- Form submission succeeds

**Step 5: Commit**

```bash
git add web/src/app/[locale]/dashboard/leases/...
git commit -m "feat: add charges, VAT toggles, agreement date, booking deposit to lease form"
```

### Task 11.2: Add "Generate Contract" preview modal

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/[id]/page.tsx`

**Step 1:** Replace the existing "Generate Contract" button with one that:
1. Calls `POST /api/v1/leases/{id}/generate-contract/preview`, gets PDF blob
2. Opens a modal showing the preview as an `<iframe src={blobUrl}>`
3. Has "Cancel" and "Confirm & Save" buttons; "Confirm & Save" calls the real `POST /api/v1/leases/{id}/generate-contract`

**Step 2:** After save, refresh the page state to show the new `contractNumber`.

**Step 3: Manual verify**

In the browser, click Generate Contract → preview modal opens with live PDF → click Confirm → page refreshes with "Contract No. 1751" visible.

**Step 4: Commit**

```bash
git add <files>
git commit -m "feat: add generate-contract preview modal with confirm/save flow"
```

### Task 11.3: Add phone + stamp to LandlordOrg profile page

**Files:**
- Modify: `web/src/app/[locale]/dashboard/profile/page.tsx` (or wherever landlord settings live)

**Step 1:** Add a Phone text input and a Stamp file upload (PNG with transparent background recommended). Show a preview thumbnail. Wire to `PATCH /api/v1/landlord-org/profile` and `POST /api/v1/landlord-org/stamp`.

**Step 2: Manual verify**

Upload a small PNG → save → reload → preview thumbnail still shown. Generate a fresh contract for a lease → stamp appears in the signature block.

**Step 3: Commit**

```bash
git add <files>
git commit -m "feat: add phone and stamp upload to LandlordOrg profile page"
```

### Task 11.4: i18n strings

**Files:**
- Modify: `web/messages/en.json`
- Modify: `web/messages/ar.json`

**Step 1:** Add keys: `lease.adminFee`, `lease.parkingRemoteFee`, `lease.vatApplicable`, `lease.bookingDeposit`, `lease.contractNumber`, `lease.agreementDate`, `landlordOrg.phone`, `landlordOrg.stamp`. English values verbatim; Arabic translations from the field labels in the PDF.

**Step 2:** Replace any hardcoded strings from Tasks 11.1–11.3 with `useTranslations()` calls.

**Step 3: Commit**

```bash
git add web/messages/en.json web/messages/ar.json web/src/...
git commit -m "feat: add i18n strings for lease agreement fields"
```

---

## Milestone 12 — Mobile Manager UI

### Task 12.1: Add new fields to lease create/edit screen

**Files:**
- Modify: `mobile/apps/manager/lib/screens/lease_detail_screen.dart`
- Modify: any "create lease" screen (search for `LeaseCreateScreen` or similar)

**Step 1:** Mirror the web form — new TextFormFields for Admin Fee, Parking Remote; SwitchListTiles for the four VAT toggles; date picker for Agreement Date; collapsible section for Booking Deposit; inline-editable "In Favour Of" per payment row.

**Step 2:** Update API payload (raw `Map<String, dynamic>` per project mobile convention).

**Step 3: Run on device**

Run: `cd mobile/apps/manager && flutter run`. Manually verify the form renders and submission succeeds.

**Step 4: Commit**

```bash
git add mobile/apps/manager/lib/...
git commit -m "feat(mobile): add charges, VAT toggles, agreement date, booking deposit to lease form"
```

### Task 12.2: Wire generate-contract button on mobile

**Files:**
- Modify: `mobile/apps/manager/lib/screens/lease_detail_screen.dart`

**Step 1:** Find existing "Generate Contract" / "Download Contract" button (if present, otherwise add). On tap:
- Call `POST /generate-contract/preview` first → open in PDF viewer (use existing PDF-view package if any; otherwise share-sheet to system viewer).
- After confirmation, call the real `generate-contract` endpoint, then refresh state to show `contractNumber`.

**Step 2:** Manually verify on device.

**Step 3: Commit**

```bash
git add mobile/apps/manager/lib/...
git commit -m "feat(mobile): add generate-contract preview flow"
```

### Task 12.3: Add phone + stamp upload to LandlordOrg settings

**Files:**
- Modify: `mobile/apps/manager/lib/screens/...` — locate the LandlordOrg / settings screen

**Step 1:** Add Phone TextFormField and Stamp image picker (use `image_picker` package — already in the project if present). Upload via the new endpoint.

**Step 2:** Manually verify.

**Step 3: Commit**

```bash
git add mobile/apps/manager/lib/...
git commit -m "feat(mobile): add LandlordOrg phone and stamp upload"
```

---

## Milestone 13 — Integration test

### Task 13.1: Full end-to-end backend integration test

**Files:**
- Create: `backend/src/test/java/com/datagami/rentaxis/api/LeaseContractGenerationIT.java`

**Step 1: Write the test**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeaseContractGenerationIT {
    // ... bootstrap a tenant, landlord-org, property, unit, renter, draft lease
    // ... call POST /generate-contract -> assert 200
    // ... call GET /documents/{docId}/download -> assert Content-Type=application/pdf, body length > 10kB
    // ... assert lease.contractNumber == 1
    // ... call generate-contract a second time on a NEW lease -> assert that lease's contractNumber == 2
}
```

**Step 2: Run**

Run: `cd backend && ./gradlew test --tests "LeaseContractGenerationIT"`
Expected: PASS.

**Step 3: Commit**

```bash
git add backend/src/test/java/com/datagami/rentaxis/api/LeaseContractGenerationIT.java
git commit -m "test: add lease contract generation integration test"
```

---

## Milestone 14 — Final verification

### Task 14.1: Manual end-to-end run-through

**Steps:**

1. `docker compose up -d` (full stack).
2. In web UI, log in as a TENANT_ADMIN.
3. Open Profile / LandlordOrg settings → set Phone = `+971 4 272 7070`, upload a stamp PNG.
4. Create a new draft lease for a residential unit:
   - Rent 55,000; Admin Fee 2,000; Deposit 3,000; Parking Remote 300
   - 4 cheque installments, payment terms = 4
   - Booking deposit 1,000 (TT, 14 Apr 2026, TRANSFER)
   - Agreement Date 17 Apr 2026
   - All VAT toggles OFF
5. Click Generate Contract → preview modal opens.
6. Verify: header shows landlord name, address, phone; Contract No 1; Agreement Date 17 Apr 2026; Tenant Information section populated; Lease Period section shows correct dates and Flat No; Section 3 has 4 rows (Rent / Admin Fee / Security Deposit / Parking Remote), all "Exempt", total 60,300; Section 4 has 5 rows ordered correctly with booking last; Amount in Words "AED Sixty Thousand Three Hundred Only"; signature block has stamp image in the middle; Pages 2–6 show the 53 numbered terms in English and Arabic.
7. Click Confirm & Save → page refreshes with `Contract No. 1`.
8. Upload a "signed" PDF as a `LeaseAttachment` — verify it appears in the attachments list.
9. Create a second lease on a COMMERCIAL property → verify VAT toggles default ON and the PDF shows 5% / VAT amounts.
10. Set Admin Fee + Parking Remote both to 0 on a third lease → verify Section 3 only shows 2 rows.

**No commit at this task — just verification.**

### Task 14.2: Open PR

**Steps:**

1. Push branch.
2. `gh pr create` with title "feat: client-format lease agreement PDF generation" and a body summarizing the milestones, the new fields, the manual test plan above, and a link to the design doc.

---

## Notes / Conventions

- **Liquibase append-only:** never modify existing changesets. The next available is 45.
- **Multi-tenancy:** `tenant_id` on Lease scopes per-LandlordOrg. Don't forget tenant filtering in any new queries.
- **Frontend API calls:** use the Next.js proxy rewrite. Never hardcode `http://localhost:8080`.
- **Mobile state:** Riverpod for state, raw `Map<String, dynamic>` for entities (no models).
- **Commits:** Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, etc.). One logical change per commit.
- **TDD:** write the failing test first; implement; verify pass; commit.
- **YAGNI:** the 53 terms are hardcoded in the template, not in a DB table. The bundle-with-first-installment logic is auto-detected — don't add a UI toggle for it. Don't add a "VAT report" — the existing one already works.
