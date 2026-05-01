# Bulk Import — Payment Schedule + New Lease Agreement Fields — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Extend the existing bulk-portfolio import (`POST /api/v1/import/portfolio`) to carry every field the new lease wizard captures: per-row payment method, per-cheque details, booking deposits, charges, VAT toggles, agreement date, per-row lease status. Old templates must keep working.

**Architecture:** Hybrid Cheques sheet (auto-distribute by `PaymentTerms` is the default; an optional Cheques sheet overrides). Switch the parser from positional reads to header-name lookup so appending columns can't break old workbooks. Booking deposit lives as four columns on the Leases sheet. Lease status is a per-row column on the Leases sheet, defaulting to `ACTIVE`. The Cheques sheet wins when both are declared.

**Tech Stack:** Java 21 + Spring Boot 4 + JPA + Apache POI 5.3.0 (existing); JUnit 5 + Mockito for tests. No new dependencies, no DB migrations, no new endpoints.

**Source design:** `docs/plans/2026-05-02-bulk-import-payment-schedule-design.md`

**Reference design (existing):** `docs/plans/2026-04-03-bulk-portfolio-import-design.md`

---

## Milestone 0 — Setup

### Task 0.1: Read the design + map the existing code

**Files:**
- Read: `docs/plans/2026-05-02-bulk-import-payment-schedule-design.md`
- Read: `backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportService.java`
- Read: `backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportPersistService.java`
- Read: `backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioTemplateService.java`
- Read: `backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java` (specifically `generateScheduleForLease`)

**Step 1:** Confirm you understand:
- The two-phase pattern (Phase 1 = validate-only via `PortfolioImportService`, Phase 2 = persist via `PortfolioImportPersistService`).
- The current Leases sheet has 10 column indexes (0–10) read positionally.
- `generateScheduleForLease` already honors `paymentTerms` and `dueDayOfMonth` correctly — the import will not re-implement schedule math, only delegate.

**Step 2:** Run existing tests to baseline:

Run: `cd backend && ./gradlew test --tests "*PortfolioImport*"`
Expected: BUILD SUCCESSFUL.

**No commit at this task.**

---

## Milestone 1 — Header-name parser foundation

### Task 1.1: Add a `HeaderIndex` helper to `PortfolioImportService`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportService.java`

**Step 1: Add a static helper class**

Inside `PortfolioImportService`, add at the bottom (before the closing brace):

```java
/**
 * Maps header names (case-insensitive, trimmed) to column indexes for a sheet.
 * Lets us read columns by name so appending new columns in
 * PortfolioTemplateService doesn't break old workbooks that omit them.
 */
static final class HeaderIndex {
    private final Map<String, Integer> byName;

    HeaderIndex(Sheet sheet) {
        Map<String, Integer> m = new HashMap<>();
        Row header = sheet.getRowNum() == 0 ? sheet.getRow(0) : sheet.getRow(sheet.getFirstRowNum());
        if (header == null) header = sheet.getRow(0);
        if (header != null) {
            for (int c = 0; c < header.getLastCellNum(); c++) {
                String v = getCellString(header, c);
                if (!v.isEmpty()) m.put(v.trim().toLowerCase(java.util.Locale.ROOT), c);
            }
        }
        this.byName = m;
    }

    /** -1 when the header isn't present (old template). */
    int col(String name) {
        Integer v = byName.get(name.toLowerCase(java.util.Locale.ROOT));
        return v == null ? -1 : v;
    }

    boolean has(String name) {
        return byName.containsKey(name.toLowerCase(java.util.Locale.ROOT));
    }
}

/** Reads a cell by header name. Returns "" when the header is absent. */
private static String cell(Row row, HeaderIndex hi, String header) {
    int c = hi.col(header);
    return c < 0 ? "" : getCellString(row, c);
}
```

Note: `getCellString` is already defined in this service. Reuse it.

**Step 2: Compile**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportService.java
git commit -m "refactor(import): add HeaderIndex helper for name-based column lookup"
```

### Task 1.2: Mirror the helper in `PortfolioImportPersistService`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportPersistService.java`

**Step 1:** Persist service does its own positional reads. Either extract `HeaderIndex` to a shared util class OR duplicate the helper here. **Pick duplication**: it's <30 lines, the two services are independent, no other consumer needs it.

Copy the same `HeaderIndex` class + `cell(Row, HeaderIndex, String)` static helper into `PortfolioImportPersistService`. Same code, same package, no extraction.

**Step 2: Compile + commit**

```bash
cd backend && ./gradlew compileJava
git add backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportPersistService.java
git commit -m "refactor(import): duplicate HeaderIndex helper into persist service"
```

---

## Milestone 2 — Extend Leases sheet schema

### Task 2.1: Update `PortfolioTemplateService` to emit new Leases columns

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioTemplateService.java` (look for the Leases-sheet header array, currently around line 84)

**Step 1: Replace the Leases header array**

Find the line that creates the Leases header (`String[] leaseHeaders = {...}`) and replace it with the full 25-column list. New columns are 11–24 (after EjariNumber):

```java
String[] leaseHeaders = {
    "PropertyName", "BuildingName", "UnitNumber", "RenterEmail",
    "StartDate", "EndDate",
    "RentAmount", "DepositAmount", "PaymentTerms", "PaymentMethod", "EjariNumber",
    // Lease-agreement fields (added 2026-05-02):
    "MonthlyRent",
    "AdminFee", "ParkingRemoteFee",
    "RentVatApplicable", "AdminFeeVatApplicable",
    "SecurityDepositVatApplicable", "ParkingRemoteVatApplicable",
    "DepositPaymentMethod", "AgreementDate", "Status",
    "BookingDeposit_Amount", "BookingDeposit_Number", "BookingDeposit_Date", "BookingDeposit_Bank"
};
```

**Step 2: Update PaymentMethod dropdown**

Find the existing `PaymentMethod` data validation (column J / index 9) and replace its allowed values to:

```java
String[] paymentMethods = {"CHEQUE", "BANK_TRANSFER", "ONLINE", "CASH"};
```

If the file has a hard-coded list `{"CHEQUE", "ONLINE"}`, replace with the four-value list. Apply the same dropdown to the `DepositPaymentMethod` column (column index 18).

**Step 3: Add a `Status` dropdown**

Right after the `PaymentMethod` validation, add a new validation on the `Status` column (index 20):

```java
String[] statuses = {"ACTIVE", "DRAFT"};
DataValidationConstraint statusConstraint = validationHelper.createExplicitListConstraint(statuses);
CellRangeAddressList statusRange = new CellRangeAddressList(1, 1000, 20, 20);
DataValidation statusValidation = validationHelper.createValidation(statusConstraint, statusRange);
sheet.addValidationData(statusValidation);
```

Match the existing dropdown setup style — read the file before editing to preserve indentation and helper-variable names.

**Step 4: Add an example row showing the new fields**

Find the example row in the Leases sheet. Add a second example row that demonstrates `MonthlyRent` + `Status=DRAFT` + a booking deposit. Keep the existing example as-is.

**Step 5: Run + check the generated workbook**

Run: `cd backend && ./gradlew bootRun &` (or rely on the already-running backend), then:

```bash
curl -s -o /tmp/template.xlsx http://localhost:8080/api/v1/import/portfolio/template -H "Authorization: Bearer ..."
unzip -l /tmp/template.xlsx | head
```

Expected: file downloads. Open in Excel/Numbers; the Leases sheet has 25 columns; the new dropdowns work.

**Step 6: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioTemplateService.java
git commit -m "feat(import): add new Leases columns + extend PaymentMethod dropdown"
```

### Task 2.2: Add a Cheques sheet to the template

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioTemplateService.java`

**Step 1: After the Leases sheet creation, create a Cheques sheet**

```java
Sheet chequesSheet = workbook.createSheet("Cheques");
String[] chequeHeaders = {
    "PropertyName", "UnitNumber", "RenterEmail",
    "InstallmentNo", "DueDate", "ChequeOrPaymentDate",
    "UniqueId", "Bank", "Amount", "Method"
};
Row chequeHeader = chequesSheet.createRow(0);
for (int i = 0; i < chequeHeaders.length; i++) {
    Cell cell = chequeHeader.createCell(i);
    cell.setCellValue(chequeHeaders[i]);
    cell.setCellStyle(headerStyle);  // reuse existing headerStyle from the file
}
```

**Step 2: Add a Method dropdown on the Cheques sheet (column 9)**

```java
DataValidationConstraint chMethod = validationHelper.createExplicitListConstraint(paymentMethods);
CellRangeAddressList chMethodRange = new CellRangeAddressList(1, 1000, 9, 9);
chequesSheet.addValidationData(validationHelper.createValidation(chMethod, chMethodRange));
```

**Step 3: Add 2-3 example cheque rows tied to the lease example**

Use the same PropertyName/UnitNumber/RenterEmail as the Leases example row so an admin can immediately see how the join works.

**Step 4: Auto-size columns + commit**

```java
for (int i = 0; i < chequeHeaders.length; i++) chequesSheet.autoSizeColumn(i);
```

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioTemplateService.java
git commit -m "feat(import): add Cheques sheet to the bulk-import template"
```

---

## Milestone 3 — Validator: header-name reads + new column rules

### Task 3.1: Switch `validateLeases` to header-name lookup (TDD)

**Files:**
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/PortfolioImportServiceTest.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportService.java`

**Step 1: Add a regression test that the OLD 10-column workbook still parses cleanly**

Use Apache POI in-memory to build a workbook matching the *old* template and feed it to `PortfolioImportService.validate(...)`. Assert no errors mention any of the new columns.

```java
@Test
void oldTemplate_withoutNewColumns_parsesWithoutErrors() throws Exception {
    Workbook wb = buildLegacyWorkbook();  // helper that creates 4 sheets with the old 10-col Leases
    var result = service.validate(wb);
    // No "MonthlyRent missing" or "Status invalid" errors — the new headers simply aren't read.
    assertThat(result.getErrors())
        .extracting(ImportErrorDTO::getField)
        .doesNotContain("MonthlyRent", "Status", "AdminFee", "RentVatApplicable");
}
```

Add a `buildLegacyWorkbook()` test helper in this test file that builds a 4-sheet workbook with the existing column layout (no new columns).

**Step 2: Run to verify it fails (current code reads positionally so no error expected — confirm it actually passes; if it does, this is a baseline guard rather than a failing test).**

Run: `cd backend && ./gradlew test --tests "PortfolioImportServiceTest.oldTemplate_withoutNewColumns_parsesWithoutErrors"`
Expected: PASS today (regression guard for tomorrow).

**Step 3: Refactor `validateLeases` to use `HeaderIndex`**

Inside `PortfolioImportService.validateLeases`, replace `getCellString(row, 0)`, `getCellString(row, 1)` etc. with `cell(row, hi, "PropertyName")`, `cell(row, hi, "BuildingName")`, etc. Build the `HeaderIndex` once at the top of the method:

```java
HeaderIndex hi = new HeaderIndex(leaseSheet);
```

Same change in any other validate-* methods that reference Leases columns by index. Validation rules and error messages stay the same — only the read mechanism changes.

**Step 4: Run all import tests**

Run: `cd backend && ./gradlew test --tests "*PortfolioImport*"`
Expected: All PASS, including the new regression test.

**Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportService.java backend/src/test/java/com/datagami/rentaxis/core/service/PortfolioImportServiceTest.java
git commit -m "refactor(import): validateLeases reads by header name, with old-template regression guard"
```

### Task 3.2: Validate new Leases-sheet columns (TDD)

**Files:**
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/PortfolioImportServiceTest.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportService.java`

**Step 1: Write failing tests for each new validation rule**

Add tests that each construct a workbook with one bad row and assert exactly one error with the right `field` value:

```java
@Test
void rentXor_bothSet_isError() {
    Workbook wb = buildLegacyWorkbook();
    setCell(wb, "Leases", 1, "RentAmount", "60000");
    setCell(wb, "Leases", 1, "MonthlyRent", "5000");
    var result = service.validate(wb);
    assertThat(result.getErrors())
        .anySatisfy(e -> {
            assertThat(e.getSheet()).isEqualTo("Leases");
            assertThat(e.getField()).isIn("RentAmount", "MonthlyRent");
            assertThat(e.getMessage()).containsIgnoringCase("exactly one");
        });
}

@Test
void rentXor_neitherSet_isError() { /* analogous */ }

@Test
void status_invalidValue_isError() { /* "PENDING" not allowed */ }

@Test
void status_blankDefaultsToActive_noError() { /* blank status passes */ }

@Test
void paymentMethod_acceptsBankTransferAndCash() { /* 4 rows, one each, no errors */ }

@Test
void bookingDeposit_partialFill_isError() { /* amount set but bank blank */ }

@Test
void agreementDate_unparseable_isError() { /* "not-a-date" */ }

@Test
void adminFee_negative_isError() { /* "-100" */ }

@Test
void vatToggle_invalidBool_isError() { /* "maybe" */ }
```

The existing test file's helpers (`setCell`, `buildLegacyWorkbook`) are the ones you added in Task 3.1.

**Step 2: Run to verify they fail**

Run: `cd backend && ./gradlew test --tests "PortfolioImportServiceTest.rentXor_bothSet_isError"` (and the others)
Expected: FAIL (validators don't exist yet).

**Step 3: Implement the validators**

In `validateLeases`, after the existing checks, add the new ones. Concrete code for each:

```java
String rentAmt = cell(row, hi, "RentAmount").trim();
String monthlyRent = cell(row, hi, "MonthlyRent").trim();
if (!rentAmt.isEmpty() && !monthlyRent.isEmpty()) {
    errors.add(new ImportErrorDTO("Leases", rowNum, "RentAmount", "Exactly one of RentAmount or MonthlyRent must be set, not both"));
}
if (rentAmt.isEmpty() && monthlyRent.isEmpty()) {
    errors.add(new ImportErrorDTO("Leases", rowNum, "RentAmount", "Exactly one of RentAmount or MonthlyRent must be set"));
}

String status = cell(row, hi, "Status").trim().toUpperCase();
if (!status.isEmpty() && !status.equals("ACTIVE") && !status.equals("DRAFT")) {
    errors.add(new ImportErrorDTO("Leases", rowNum, "Status", "Status must be ACTIVE or DRAFT"));
}

// VAT toggles — accept true/false/yes/no/1/0/blank, anything else is an error
for (String h : List.of("RentVatApplicable", "AdminFeeVatApplicable",
                         "SecurityDepositVatApplicable", "ParkingRemoteVatApplicable")) {
    String v = cell(row, hi, h).trim().toLowerCase();
    if (!v.isEmpty() && !VALID_BOOLS.contains(v)) {
        errors.add(new ImportErrorDTO("Leases", rowNum, h, "Must be true/false/yes/no/1/0 or blank"));
    }
}
// Define VALID_BOOLS = Set.of("true","false","yes","no","1","0") as a constant on the class.

// Numeric ≥ 0 fields
for (String h : List.of("AdminFee", "ParkingRemoteFee")) {
    String v = cell(row, hi, h).trim();
    if (!v.isEmpty()) {
        try {
            BigDecimal n = new BigDecimal(v);
            if (n.signum() < 0) errors.add(new ImportErrorDTO("Leases", rowNum, h, h + " cannot be negative"));
        } catch (NumberFormatException e) {
            errors.add(new ImportErrorDTO("Leases", rowNum, h, h + " must be a number"));
        }
    }
}

// AgreementDate
String agreementDate = cell(row, hi, "AgreementDate").trim();
if (!agreementDate.isEmpty()) {
    try { LocalDate.parse(agreementDate); }
    catch (DateTimeParseException e) {
        errors.add(new ImportErrorDTO("Leases", rowNum, "AgreementDate", "AgreementDate must be ISO format (YYYY-MM-DD)"));
    }
}

// BookingDeposit all-or-nothing + amount > 0
String bdAmt = cell(row, hi, "BookingDeposit_Amount").trim();
String bdNum = cell(row, hi, "BookingDeposit_Number").trim();
String bdDate = cell(row, hi, "BookingDeposit_Date").trim();
String bdBank = cell(row, hi, "BookingDeposit_Bank").trim();
boolean anyBd = !(bdAmt + bdNum + bdDate + bdBank).isEmpty();
boolean allBd = !bdAmt.isEmpty() && !bdNum.isEmpty() && !bdDate.isEmpty() && !bdBank.isEmpty();
if (anyBd && !allBd) {
    errors.add(new ImportErrorDTO("Leases", rowNum, "BookingDeposit_Amount", "All four BookingDeposit_* columns must be set together"));
}
if (!bdAmt.isEmpty()) {
    try {
        BigDecimal n = new BigDecimal(bdAmt);
        if (n.signum() <= 0) errors.add(new ImportErrorDTO("Leases", rowNum, "BookingDeposit_Amount", "BookingDeposit_Amount must be > 0"));
    } catch (NumberFormatException e) {
        errors.add(new ImportErrorDTO("Leases", rowNum, "BookingDeposit_Amount", "Must be a number"));
    }
}

// PaymentMethod / DepositPaymentMethod accept the four enum values
Set<String> validMethods = Set.of("CHEQUE", "BANK_TRANSFER", "ONLINE", "CASH");
for (String h : List.of("PaymentMethod", "DepositPaymentMethod")) {
    String v = cell(row, hi, h).trim().toUpperCase();
    if (!v.isEmpty() && !validMethods.contains(v)) {
        errors.add(new ImportErrorDTO("Leases", rowNum, h, h + " must be one of " + validMethods));
    }
}
```

(The existing code's `validPaymentMethods` set was based on `PaymentMethod.values()` — that already includes BANK_TRANSFER and CASH after the earlier enum extension, so it doesn't need changes. Just double-check during this task and remove the duplicate constant if you keep both.)

**Step 4: Run tests**

Run: `cd backend && ./gradlew test --tests "*PortfolioImportServiceTest*"`
Expected: All PASS.

**Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportService.java backend/src/test/java/com/datagami/rentaxis/core/service/PortfolioImportServiceTest.java
git commit -m "feat(import): validate new Leases columns (rent xor, status, VAT, charges, booking deposit)"
```

---

## Milestone 4 — Cheques sheet validation

### Task 4.1: Validate the optional Cheques sheet (TDD)

**Files:**
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/PortfolioImportServiceTest.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportService.java`

**Step 1: Add `validateCheques` failing tests**

```java
@Test
void chequesSheet_absent_isFine() { /* legacy workbook, no Cheques sheet */ }

@Test
void chequesSheet_referencingMissingLease_isError() {
    // Cheque row's PropertyName/UnitNumber/RenterEmail doesn't match any Leases row
}

@Test
void chequesSheet_duplicateInstallmentNo_isError() {
    // Two rows with InstallmentNo=2 for the same lease
}

@Test
void chequesSheet_sumNotEqualTotalRent_isError() {
    // Lease totalRent=60000, cheques sum=55000 → error
}

@Test
void chequesSheet_chequeRowMissingChequeNumber_isError_whenMethodIsCheque() { /* */ }

@Test
void chequesSheet_cashRowOmitsBank_isFine() { /* CASH doesn't require bank */ }

@Test
void chequesSheet_dueDateOutsideLease_isWarning_notError() { /* warning collected separately */ }
```

**Step 2: Run to verify they fail**

Run: `./gradlew test --tests "PortfolioImportServiceTest.chequesSheet_*"`
Expected: FAIL.

**Step 3: Implement `validateCheques`**

```java
private void validateCheques(Workbook wb,
                             Map<String, LeaseRowSummary> leaseIndex,
                             List<ImportErrorDTO> errors,
                             List<ImportErrorDTO> warnings) {
    Sheet sheet = wb.getSheet("Cheques");
    if (sheet == null) return;
    HeaderIndex hi = new HeaderIndex(sheet);
    Set<String> validMethods = Set.of("CHEQUE", "BANK_TRANSFER", "ONLINE", "CASH");

    Map<String, Map<Integer, BigDecimal>> sumsByLease = new HashMap<>();
    Map<String, Set<Integer>> seenInstallments = new HashMap<>();

    for (int r = 1; r <= sheet.getLastRowNum(); r++) {
        Row row = sheet.getRow(r);
        if (row == null || isRowEmpty(row, hi)) continue;
        int rowNum = r + 1;

        String pname = cell(row, hi, "PropertyName").trim();
        String unum = cell(row, hi, "UnitNumber").trim();
        String email = cell(row, hi, "RenterEmail").trim();
        String key = pname + "|" + unum + "|" + email;
        LeaseRowSummary lease = leaseIndex.get(key);
        if (lease == null) {
            errors.add(new ImportErrorDTO("Cheques", rowNum, "PropertyName",
                "No Leases row matches " + pname + " / " + unum + " / " + email));
            continue;
        }

        // InstallmentNo unique per lease
        String inoStr = cell(row, hi, "InstallmentNo").trim();
        int ino;
        try {
            ino = Integer.parseInt(inoStr);
            if (ino < 1) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            errors.add(new ImportErrorDTO("Cheques", rowNum, "InstallmentNo", "Must be a positive integer"));
            continue;
        }
        Set<Integer> seen = seenInstallments.computeIfAbsent(key, k -> new HashSet<>());
        if (!seen.add(ino)) {
            errors.add(new ImportErrorDTO("Cheques", rowNum, "InstallmentNo",
                "Duplicate InstallmentNo " + ino + " for this lease"));
        }

        // Method (default = lease's method)
        String method = cell(row, hi, "Method").trim().toUpperCase();
        if (method.isEmpty()) method = lease.paymentMethod;
        if (!validMethods.contains(method)) {
            errors.add(new ImportErrorDTO("Cheques", rowNum, "Method", "Must be one of " + validMethods));
            continue;
        }

        // Method-driven required fields
        String uniqueId = cell(row, hi, "UniqueId").trim();
        String bank = cell(row, hi, "Bank").trim();
        String chequeDate = cell(row, hi, "ChequeOrPaymentDate").trim();
        String dueDate = cell(row, hi, "DueDate").trim();
        if (dueDate.isEmpty()) {
            errors.add(new ImportErrorDTO("Cheques", rowNum, "DueDate", "DueDate is required"));
        }
        switch (method) {
            case "CHEQUE":
                if (uniqueId.isEmpty() || chequeDate.isEmpty() || bank.isEmpty()) {
                    errors.add(new ImportErrorDTO("Cheques", rowNum, "UniqueId",
                        "CHEQUE rows require UniqueId, ChequeOrPaymentDate, and Bank"));
                }
                break;
            case "BANK_TRANSFER":
            case "ONLINE":
                if (bank.isEmpty() || chequeDate.isEmpty()) {
                    errors.add(new ImportErrorDTO("Cheques", rowNum, "Bank",
                        method + " rows require Bank and ChequeOrPaymentDate"));
                }
                break;
            case "CASH":
                // amount + due date only; nothing else required
                break;
        }

        // Amount
        String amtStr = cell(row, hi, "Amount").trim();
        BigDecimal amt = BigDecimal.ZERO;
        try {
            amt = new BigDecimal(amtStr);
            if (amt.signum() < 0) {
                errors.add(new ImportErrorDTO("Cheques", rowNum, "Amount", "Amount cannot be negative"));
            }
        } catch (NumberFormatException e) {
            errors.add(new ImportErrorDTO("Cheques", rowNum, "Amount", "Must be a number"));
        }

        // DueDate-outside-lease — warning, not error
        try {
            LocalDate dd = LocalDate.parse(dueDate);
            if (dd.isBefore(lease.startDate) || dd.isAfter(lease.endDate)) {
                warnings.add(new ImportErrorDTO("Cheques", rowNum, "DueDate",
                    "DueDate " + dd + " is outside lease period " + lease.startDate + "..." + lease.endDate));
            }
        } catch (DateTimeParseException e) {
            errors.add(new ImportErrorDTO("Cheques", rowNum, "DueDate", "DueDate must be ISO format"));
        }

        sumsByLease.computeIfAbsent(key, k -> new HashMap<>())
                   .merge(ino, amt, BigDecimal::add);
    }

    // After all rows: sum check per lease
    BigDecimal tolerance = new BigDecimal("1.00");
    for (var entry : sumsByLease.entrySet()) {
        LeaseRowSummary lease = leaseIndex.get(entry.getKey());
        BigDecimal sum = entry.getValue().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.subtract(lease.totalRent).abs().compareTo(tolerance) > 0) {
            errors.add(new ImportErrorDTO("Cheques", 0, "Amount",
                "Sum of cheques (" + sum + ") does not match lease total rent (" + lease.totalRent + ") for "
                + entry.getKey()));
        }
    }
}

private static record LeaseRowSummary(String paymentMethod, BigDecimal totalRent,
                                      LocalDate startDate, LocalDate endDate) {}
```

`leaseIndex` is built during `validateLeases` keyed by `PropertyName|UnitNumber|RenterEmail`. Add a step at the bottom of `validateLeases` to populate it and pass it into `validateCheques` from the top-level `validate` method.

**Step 4:** Decide whether warnings flow into the existing `errors` list or a new `warnings` field on the result. **For now, surface as warnings** by adding `warnings: List<ImportError>` to `PortfolioImportResultDTO` (additive, won't break old consumers). UI shows them in a separate yellow banner. Keep the `errors` list as a hard-fail gate.

**Step 5:** Run tests, commit.

```bash
cd backend && ./gradlew test --tests "*PortfolioImportServiceTest*"
git add backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportService.java \
        backend/src/main/java/com/datagami/rentaxis/api/dto/PortfolioImportResultDTO.java \
        backend/src/test/java/com/datagami/rentaxis/core/service/PortfolioImportServiceTest.java
git commit -m "feat(import): validate Cheques sheet — references, method-driven required fields, sum-eq-total"
```

---

## Milestone 5 — Persist new fields

### Task 5.1: Persist new Leases-row fields (TDD)

**Files:**
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/PortfolioImportPersistServiceTest.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportPersistService.java`

**Step 1: Failing tests**

```java
@Test
void persist_setsAllNewLeaseFields() throws Exception {
    Workbook wb = buildWorkbookWithOneLease(builder -> builder
        .adminFee("500").parkingRemoteFee("100")
        .rentVat(true).adminVat(true).depositVat(false).parkingVat(false)
        .agreementDate("2026-05-01")
        .depositPaymentMethod("BANK_TRANSFER")
        .status("DRAFT"));
    persistService.persist(wb, /* tenantId */, /* createdBy */);

    Lease saved = leaseRepository.findAll().get(0);
    assertThat(saved.getStatus()).isEqualTo(LeaseStatus.DRAFT);
    assertThat(saved.getAdminFee()).isEqualByComparingTo("500");
    assertThat(saved.getParkingRemoteFee()).isEqualByComparingTo("100");
    assertThat(saved.isRentVatApplicable()).isTrue();
    assertThat(saved.isAdminFeeVatApplicable()).isTrue();
    assertThat(saved.isSecurityDepositVatApplicable()).isFalse();
    assertThat(saved.isParkingRemoteVatApplicable()).isFalse();
    assertThat(saved.getAgreementDate()).isEqualTo(LocalDate.parse("2026-05-01"));
    assertThat(saved.getDepositPaymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
}

@Test
void persist_draftStatus_keepsUnitVacant() { /* unit.status remains VACANT */ }

@Test
void persist_activeStatus_setsUnitOccupied() { /* unit.status becomes OCCUPIED — current behavior */ }

@Test
void persist_monthlyRent_computesTotalCorrectly() {
    // 12-month lease, MonthlyRent=5000 (no RentAmount column),
    // assert lease.rentAmount == 60000 and lease.monthlyRent == 5000
}

@Test
void persist_rentAmount_computesMonthlyCorrectly() {
    // 12-month lease, RentAmount=60000, paymentTerms=4
    // BUG-FIX: monthly should be 60000/12 = 5000, NOT 60000/4 = 15000.
    // Current code computes the wrong thing; fixing it is part of this task.
}

@Test
void persist_bookingDeposit_savesBookingPaymentScheduleRow() {
    // assert one PaymentSchedule with isBookingDeposit=true, the right amount/cheque/bank
}

@Test
void persist_vatDefaultsFromCommercialProperty_whenTogglesBlank() {
    // property.type=COMMERCIAL, all four VAT cells blank → all four flags persist as true
}
```

**Step 2: Run to verify they fail**

Run: `./gradlew test --tests "PortfolioImportPersistServiceTest"`
Expected: FAIL (these fields aren't read yet, monthly-rent bug present).

**Step 3: Implement reads + persistence**

In the Leases-loop of `persist`, replace positional `getCellString(row, ...)` reads with `cell(row, hi, ...)`. Add reads for all new headers. Compute `monthlyRent`/`totalRent` per the design's Phase-2 step 2:

```java
long monthsBetween = Math.max(ChronoUnit.MONTHS.between(startDate, endDate), 1);
String rentAmtStr = cell(row, hi, "RentAmount").trim();
String monthlyRentStr = cell(row, hi, "MonthlyRent").trim();
BigDecimal monthlyRent;
BigDecimal totalRent;
if (!monthlyRentStr.isEmpty()) {
    monthlyRent = new BigDecimal(monthlyRentStr);
    totalRent = monthlyRent.multiply(BigDecimal.valueOf(monthsBetween));
} else {
    totalRent = new BigDecimal(rentAmtStr);
    monthlyRent = totalRent.divide(BigDecimal.valueOf(monthsBetween), 2, RoundingMode.HALF_UP);
}
lease.setRentAmount(totalRent);
lease.setMonthlyRent(monthlyRent);
```

Then set the new fields:

```java
lease.setAdminFee(parseDecimalOrZero(cell(row, hi, "AdminFee")));
lease.setParkingRemoteFee(parseDecimalOrZero(cell(row, hi, "ParkingRemoteFee")));
boolean commercialDefault = unit.getProperty().getType() == PropertyType.COMMERCIAL;
lease.setRentVatApplicable(parseBoolOrDefault(cell(row, hi, "RentVatApplicable"), commercialDefault));
lease.setAdminFeeVatApplicable(parseBoolOrDefault(cell(row, hi, "AdminFeeVatApplicable"), commercialDefault));
lease.setSecurityDepositVatApplicable(parseBoolOrDefault(cell(row, hi, "SecurityDepositVatApplicable"), commercialDefault));
lease.setParkingRemoteVatApplicable(parseBoolOrDefault(cell(row, hi, "ParkingRemoteVatApplicable"), commercialDefault));
String depMethod = cell(row, hi, "DepositPaymentMethod").trim().toUpperCase();
if (!depMethod.isEmpty()) lease.setDepositPaymentMethod(PaymentMethod.valueOf(depMethod));
String agreementStr = cell(row, hi, "AgreementDate").trim();
if (!agreementStr.isEmpty()) lease.setAgreementDate(LocalDate.parse(agreementStr));

String statusStr = cell(row, hi, "Status").trim().toUpperCase();
LeaseStatus leaseStatus = statusStr.equals("DRAFT") ? LeaseStatus.DRAFT : LeaseStatus.ACTIVE;
lease.setStatus(leaseStatus);
```

Add the helpers `parseDecimalOrZero` and `parseBoolOrDefault` at the bottom of the service.

After `leaseRepository.save(lease)`, persist booking deposit if columns are populated:

```java
String bdAmt = cell(row, hi, "BookingDeposit_Amount").trim();
if (!bdAmt.isEmpty()) {
    PaymentSchedule booking = new PaymentSchedule();
    booking.setLease(lease);
    booking.setUnit(unit);
    booking.setProperty(unit.getProperty());
    booking.setInstallmentNumber(0);
    booking.setAmount(new BigDecimal(bdAmt));
    booking.setChequeNumber(emptyToNull(cell(row, hi, "BookingDeposit_Number")));
    booking.setChequeDate(LocalDate.parse(cell(row, hi, "BookingDeposit_Date").trim()));
    booking.setBankName(emptyToNull(cell(row, hi, "BookingDeposit_Bank")));
    booking.setDueDate(booking.getChequeDate());
    booking.setStatus(PaymentStatus.PENDING);
    booking.setPaymentMethod(lease.getPaymentMethod() != null ? lease.getPaymentMethod().name() : "CHEQUE");
    booking.setPurposeLabel("BOOKING RECEIVED");
    booking.setBookingDeposit(true);
    paymentScheduleRepository.save(booking);
    bookingDepositsCreated++;
}
```

Update the `Status`-driven unit transition:

```java
if (leaseStatus == LeaseStatus.ACTIVE) {
    unit.setStatus(UnitStatus.OCCUPIED);
    unitRepository.save(unit);
}
// DRAFT → leave unit VACANT
```

**Step 4: Run tests**

Run: `./gradlew test --tests "PortfolioImportPersistServiceTest"`
Expected: All PASS.

**Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportPersistService.java backend/src/test/java/com/datagami/rentaxis/core/service/PortfolioImportPersistServiceTest.java
git commit -m "feat(import): persist new lease fields (charges, VAT, agreement date, status, booking deposit)"
```

---

## Milestone 6 — Schedule generation: auto-distribute or Cheques-sheet override

### Task 6.1: Auto-distribute when no Cheques rows (TDD)

**Files:**
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/PortfolioImportPersistServiceTest.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportPersistService.java`

**Step 1: Failing test**

```java
@Test
void persist_noChequesSheet_autoDistributesPerPaymentTerms() throws Exception {
    // 12-month lease, paymentTerms=4 → expect 4 schedule rows at month offsets 0,3,6,9
    var wb = buildWorkbookWithOneLease(b -> b.paymentTerms(4));
    persistService.persist(wb, ...);
    var schedules = paymentScheduleRepository.findByLeaseId(savedLeaseId);
    assertThat(schedules).hasSize(4);
    assertThat(schedules).extracting(PaymentSchedule::getInstallmentNumber)
        .containsExactlyInAnyOrder(1, 2, 3, 4);
    // each amount = totalRent / 4 (last takes remainder)
}
```

**Step 2: Run, fail, implement**

In `persist`, after the lease is saved (and any booking deposit), inject `PaymentScheduleService` and call:

```java
paymentScheduleService.generateScheduleForLease(lease);
schedulesCreated += lease.getPaymentTerms();  // approximation; or count returned list
```

This delegates to the existing N-installments generator (which honors `paymentTerms` + `dueDayOfMonth`).

Inject `PaymentScheduleService` into `PortfolioImportPersistService` via constructor (Spring will wire it; existing pattern in the file).

**Step 3: Run + commit**

```bash
cd backend && ./gradlew test --tests "*PortfolioImportPersistServiceTest*"
git add ...
git commit -m "feat(import): auto-distribute schedule via PaymentScheduleService when no Cheques sheet"
```

### Task 6.2: Cheques-sheet override (TDD)

**Files:**
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/PortfolioImportPersistServiceTest.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportPersistService.java`

**Step 1: Failing tests**

```java
@Test
void persist_chequesSheetRows_overridePaymentTerms() {
    // Lease has paymentTerms=4 in Leases sheet, Cheques sheet has 5 rows for it.
    // After persist: 5 schedule rows + lease.paymentTerms == 5.
}

@Test
void persist_chequesSheet_perRowMethodAndChequeDetailsPreserved() {
    // 4 cheque rows, mixed CHEQUE/CASH/BANK_TRANSFER methods, distinct cheque#/bank.
    // Schedule rows reflect each.
}

@Test
void persist_chequesSheet_firstInstallmentLabelHasBundleSuffixWhenChargesPresent() {
    // adminFee=500, depositAmount=2000 → installment 1 purposeLabel ends with "/ADMIN/SD/REMOTE"
}
```

**Step 2: Implementation outline**

Before the auto-distribute call, build a per-lease cheques map from the Cheques sheet (in a single pass at the top of `persist`, similar to how leaseIndex is built in the validator):

```java
Map<String, List<ChequeRowDTO>> chequesByLeaseKey = readChequesSheet(wb);
```

Inside the leases loop, after lease.save:

```java
String key = pname + "|" + unum + "|" + email;
List<ChequeRowDTO> chequeRows = chequesByLeaseKey.getOrDefault(key, List.of());
if (chequeRows.isEmpty()) {
    paymentScheduleService.generateScheduleForLease(lease);
    schedulesCreated += lease.getPaymentTerms();
} else {
    chequeRows.sort(Comparator.comparingInt(ChequeRowDTO::installmentNo));
    lease.setPaymentTerms(chequeRows.size());
    leaseRepository.save(lease);  // refresh
    for (ChequeRowDTO ch : chequeRows) {
        PaymentSchedule ps = new PaymentSchedule();
        ps.setLease(lease);
        ps.setUnit(unit);
        ps.setProperty(unit.getProperty());
        ps.setInstallmentNumber(ch.installmentNo);
        ps.setDueDate(ch.dueDate);
        ps.setChequeDate(ch.chequeDate);
        ps.setChequeNumber(ch.uniqueId);
        ps.setBankName(ch.bank);
        ps.setAmount(ch.amount);
        ps.setStatus(PaymentStatus.PENDING);
        ps.setPaymentMethod(ch.method);
        // Match the wizard's labeling: "RENT - 1ST INSTALLMENT", append /ADMIN/SD/REMOTE
        // on installment 1 if any of admin/SD/parking > 0 (mirror PaymentScheduleService.ordinalOf).
        String label = "RENT - " + ordinalOf(ch.installmentNo) + " INSTALLMENT";
        if (ch.installmentNo == 1) {
            boolean bundled = lease.getAdminFee().signum() > 0
                    || lease.getDepositAmount().signum() > 0
                    || lease.getParkingRemoteFee().signum() > 0;
            if (bundled) label += "/ADMIN/SD/REMOTE";
        }
        ps.setPurposeLabel(label);
        paymentScheduleRepository.save(ps);
    }
    schedulesCreated += chequeRows.size();
    chequesFromSheet += chequeRows.size();
}
```

`ordinalOf` is a private helper on `PaymentScheduleService`. Either expose it or duplicate it locally. **Pick: duplicate locally** (it's 5 lines and not worth shifting visibility for one caller).

**Step 3: Run + commit**

```bash
cd backend && ./gradlew test --tests "*PortfolioImportPersistServiceTest*"
git add ...
git commit -m "feat(import): persist Cheques-sheet rows directly, overriding paymentTerms"
```

---

## Milestone 7 — Result DTO + integration test

### Task 7.1: Add new counters to `PortfolioImportResultDTO`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/dto/PortfolioImportResultDTO.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PortfolioImportPersistService.java`

**Step 1:** Add two `int` fields with getters/setters/Lombok annotations:

```java
private int chequesFromSheet;
private int bookingDepositsCreated;
```

`warnings: List<ImportError>` was added in Task 4.1 — confirm it's still there.

**Step 2:** In the persist service, increment these counters in the appropriate spots and set them on the result DTO at the end. Compile + run all import tests.

**Step 3: Commit**

```bash
git add ...
git commit -m "feat(import): expose chequesFromSheet and bookingDepositsCreated counters"
```

### Task 7.2: End-to-end integration test

**Files:**
- Create: `backend/src/test/resources/portfolio-import/sample-with-cheques.xlsx` (binary fixture; build it programmatically inside the test setup if easier than committing a binary)
- Create: `backend/src/test/java/com/datagami/rentaxis/api/PortfolioImportIT.java`

**Step 1: Build a workbook covering the five scenarios:**
1. Lease with auto-distribute (no Cheques rows, paymentTerms=4)
2. Lease with Cheques sheet rows (4 rows, mixed methods)
3. Lease with Status=DRAFT
4. Lease with booking deposit
5. Lease using MonthlyRent instead of RentAmount

**Step 2: Test:**

```java
@SpringBootTest
class PortfolioImportIT {
    @Test
    void uploadWorkbookWithAllNewFields_persistsCorrectly() throws Exception {
        Workbook wb = buildSampleWorkbook();
        UUID jobId = startImport(wb);
        pollUntilCompleted(jobId);

        PortfolioImportResultDTO result = getResult(jobId);
        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getLeasesCreated()).isEqualTo(5);
        assertThat(result.getChequesFromSheet()).isEqualTo(4);
        assertThat(result.getBookingDepositsCreated()).isEqualTo(1);

        // Spot-check one persisted lease (Status=DRAFT)
        Lease draft = leaseRepository.findAll().stream()
            .filter(l -> l.getStatus() == LeaseStatus.DRAFT)
            .findFirst().orElseThrow();
        assertThat(draft.getUnit().getStatus()).isEqualTo(UnitStatus.VACANT);
    }
}
```

**Step 3: Run + commit**

```bash
cd backend && ./gradlew test --tests "*PortfolioImportIT*"
git add ...
git commit -m "test(import): end-to-end IT for new lease fields + Cheques sheet"
```

---

## Milestone 8 — Final verification + PR

### Task 8.1: Full backend test pass + manual smoke

**Steps:**

1. Run: `cd backend && ./gradlew clean test`
   Expected: BUILD SUCCESSFUL.
2. Restart backend; download the new template; verify visually that:
   - Leases sheet has 25 columns with the new headers.
   - Cheques sheet exists with 10 columns + 2-3 example rows.
   - Dropdowns: PaymentMethod, DepositPaymentMethod, Status, Method (on Cheques) all show four entries / two entries respectively.
3. Upload an old (pre-2026-05-02) template — must succeed without errors.
4. Upload a new template with one lease per scenario — must produce one COMPLETED job with all five leases persisted.

**No commit at this task — verification only.**

### Task 8.2: Open PR

**Steps:**

1. Push the branch (or rebase + push if working in a worktree).
2. `gh pr create --title "feat: bulk import — payment schedule + new lease agreement fields"` with a body that links the design doc, lists the milestones, and references the manual test plan in 8.1.

---

## Notes / Conventions

- **Liquibase append-only:** still no migrations needed for this feature.
- **Multi-tenancy:** the persist service already runs with `tenantId` set; new code only adds reads/writes via existing repositories which honor the tenant filter.
- **Frontend:** unchanged. The existing import modal at `/dashboard/properties` uploads any .xlsx.
- **Mobile:** unchanged.
- **Commits:** Conventional Commits. One logical change per commit. `feat(import):`, `refactor(import):`, `test(import):`.
- **TDD:** tests written before implementation for every behavior change. Validators and persistence both have failing tests committed first.
- **YAGNI:** no Cheques-sheet for booking deposits, no separate Charges sheet, no Excel formula support, no per-cell cell-comments, no schema versioning header.
