package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.domain.repository.*;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the bulk-import persist service. Repositories are mocked; save()
 * returns the same entity, so the captured arguments reflect the in-memory state
 * the service produced.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioImportPersistServiceTest {

    @Mock PropertyRepository propertyRepository;
    @Mock BuildingRepository buildingRepository;
    @Mock UnitRepository unitRepository;
    @Mock RenterRepository renterRepository;
    @Mock LeaseRepository leaseRepository;
    @Mock ImportJobRepository importJobRepository;
    @Mock LeaseService leaseService;
    @Mock ChargeTypeService chargeTypeService;
    @Mock ChequeGenerationService chequeGenerationService;
    @Mock com.datagami.rentaxis.core.service.ledger.PropertyAccountService propertyAccountService;
    @Mock com.datagami.rentaxis.core.service.cutover.ContractImportLeasePoster leasePoster;

    PortfolioImportPersistService service;

    @BeforeEach
    void setUp() {
        service = new PortfolioImportPersistService(
                propertyRepository, buildingRepository, unitRepository,
                renterRepository, leaseRepository, importJobRepository,
                leaseService, chargeTypeService, chequeGenerationService,
                propertyAccountService, leasePoster);
        // A generated grid is one row big enough for any booking cheque; the
        // non-rent proposal (a Cheques-sheet lease) is empty. The real proposals are
        // ChequeGenerationServiceIT's and PortfolioImportPostingIT's subject.
        lenient().when(chequeGenerationService.proposeForSystemImport(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(inv -> new ChequeGenerationService.Proposal((Boolean) inv.getArgument(2)
                        ? java.util.List.of(new ChequeGenerationService.Row(1, java.time.LocalDate.of(2026, 1, 1),
                                java.time.LocalDate.of(2026, 1, 1), new java.math.BigDecimal("1000000"),
                                "Rent - 1st Installment"))
                        : java.util.List.of(), null));
        // The grid itself is ChequeGenerationServiceIT's subject; here the question
        // is only which rows the import hands it.
        lenient().when(chequeGenerationService.generateForSystemImport(any(), any())).thenReturn(List.of());
        lenient().when(chequeGenerationService.saveRowsForSystemImport(any(), any())).thenReturn(List.of());

        // save(...) → return the input entity, simulating ID assignment.
        lenient().when(propertyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(buildingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(unitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(renterRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(leaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(importJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * The lines the import handed to {@link LeaseService#applyLines}. The import
     * no longer writes charge or schedule rows of its own — it builds the same
     * line inputs the draft wizard does and lets the lease service validate them,
     * so the inputs are what these tests can assert on.
     */
    @SuppressWarnings("unchecked")
    private List<LeaseLineInput> captureSavedLines() {
        ArgumentCaptor<List<LeaseLineInput>> cap = ArgumentCaptor.forClass(List.class);
        verify(leaseService, atLeastOnce()).applyLines(any(Lease.class), cap.capture());
        return cap.getValue();
    }

    private static LeaseLineInput lineOf(List<LeaseLineInput> lines, String code) {
        return lines.stream().filter(l -> code.equals(l.chargeTypeCode())).findFirst()
                .orElseThrow(() -> new AssertionError("expected a " + code + " line; got " + lines));
    }

    @Test
    void persist_setsAllNewLeaseFields() {
        Workbook wb = buildWorkbookWithOneLease(b -> b
                .adminFee("500").parkingRemoteFee("100")
                .rentVat("true").adminVat("true").depositVat("false").parkingVat("false")
                .agreementDate("2026-05-01")
                .depositPaymentMethod("BANK_TRANSFER")
                .status("DRAFT"));

        service.persistWorkbook(wb, newJob());

        Lease saved = captureSavedLease();
        assertThat(saved.getStatus()).isEqualTo(LeaseStatus.DRAFT);
        assertThat(saved.isRentVatApplicable()).isTrue();
        assertThat(saved.getAgreementDate()).isEqualTo(LocalDate.parse("2026-05-01"));
        assertThat(saved.getDepositPaymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);

        // Every money column on the sheet becomes a charge line, named by
        // catalogue code, with its VAT intent carried from the per-fee column.
        List<LeaseLineInput> lines = captureSavedLines();
        assertThat(lines).extracting(LeaseLineInput::chargeTypeCode)
                .containsExactly("RENT", "SECURITY_DEPOSIT", "ADMIN_FEE", "PARKING_FEE");
        assertThat(lineOf(lines, "ADMIN_FEE").grossAmount()).isEqualByComparingTo("500");
        assertThat(lineOf(lines, "ADMIN_FEE").vatApplicable()).isTrue();
        assertThat(lineOf(lines, "PARKING_FEE").grossAmount()).isEqualByComparingTo("100");
        assertThat(lineOf(lines, "PARKING_FEE").vatApplicable()).isFalse();
        // The rent line covers the term, which is what per-day recognition divides by.
        assertThat(lineOf(lines, "RENT").periodStart()).isEqualTo(saved.getStartDate());
        assertThat(lineOf(lines, "RENT").periodEnd()).isEqualTo(saved.getEndDate());
    }

    @Test
    void persist_draftStatus_keepsUnitVacant() {
        Workbook wb = buildWorkbookWithOneLease(b -> b.status("DRAFT"));

        service.persistWorkbook(wb, newJob());

        Unit unit = captureLastSavedUnit();
        assertThat(unit.getStatus())
                .as("DRAFT lease must NOT flip the unit to OCCUPIED")
                .isEqualTo(UnitStatus.VACANT);
    }

    /**
     * Gap #83: an ACTIVE row is written DRAFT, its unit left VACANT, and handed to
     * the post phase — which is the only thing that may make it ACTIVE. The persist
     * phase used to set ACTIVE and OCCUPIED itself, putting unposted tenancies on
     * the dashboard.
     */
    @Test
    void persist_activeStatus_isWrittenDraftAndQueuedForPosting() {
        Workbook wb = buildWorkbookWithOneLease(b -> b.status("ACTIVE"));

        PortfolioImportPersistService.PersistResult result = service.persistWorkbook(wb, newJob());

        assertThat(captureSavedLease().getStatus()).isEqualTo(LeaseStatus.DRAFT);
        assertThat(captureLastSavedUnit().getStatus()).isEqualTo(UnitStatus.VACANT);
        assertThat(result.toPost()).singleElement()
                .satisfies(p -> assertThat(p.rowNum()).isEqualTo(2));
        verify(leasePoster, org.mockito.Mockito.never()).postPortfolioLease(any());
    }

    @Test
    void persist_blankStatus_defaultsToActive() {
        Workbook wb = buildWorkbookWithOneLease(b -> b.status(""));

        PortfolioImportPersistService.PersistResult result = service.persistWorkbook(wb, newJob());

        assertThat(captureSavedLease().getStatus()).isEqualTo(LeaseStatus.DRAFT);
        assertThat(result.toPost()).hasSize(1);
    }

    @Test
    void persist_draftStatus_isNotQueuedForPosting() {
        Workbook wb = buildWorkbookWithOneLease(b -> b.status("DRAFT"));

        assertThat(service.persistWorkbook(wb, newJob()).toPost()).isEmpty();
    }

    /** The post phase: a refusal leaves the lease DRAFT and says so against its row. */
    @Test
    void postRequested_aRefusedPost_isListedAsImportedAsDraft() throws Exception {
        Workbook wb = buildWorkbookWithOneLease(b -> b.status("ACTIVE"));
        ImportJob job = newJob();
        PortfolioImportPersistService.PersistResult result = service.persistWorkbook(wb, job);
        org.mockito.Mockito.when(leasePoster.postPortfolioLease(any())).thenThrow(
                new com.datagami.rentaxis.api.exception.BusinessRuleViolationException(
                        "Cheque grid totals 38,000.00 but contract value is 42,800.00."));

        service.postRequested(result, job);

        var details = new com.fasterxml.jackson.databind.ObjectMapper().readValue(job.getErrors(),
                com.datagami.rentaxis.api.dto.PortfolioImportJobDetailsDTO.class);
        assertThat(details.getLeasesPosted()).isZero();
        assertThat(details.getWarnings()).singleElement().satisfies(w -> {
            assertThat(w.getSheet()).isEqualTo("Leases");
            assertThat(w.getRow()).isEqualTo(2);
            assertThat(w.getMessage()).isEqualTo(
                    "Imported as draft: Cheque grid totals 38,000.00 but contract value is 42,800.00.");
        });
    }

    @Test
    void postRequested_countsWhatPosted() throws Exception {
        Workbook wb = buildWorkbookWithOneLease(b -> b.status("ACTIVE"));
        ImportJob job = newJob();

        service.postRequested(service.persistWorkbook(wb, job), job);

        var details = new com.fasterxml.jackson.databind.ObjectMapper().readValue(job.getErrors(),
                com.datagami.rentaxis.api.dto.PortfolioImportJobDetailsDTO.class);
        assertThat(details.getLeasesPosted()).isEqualTo(1);
        assertThat(details.getWarnings()).isNullOrEmpty();
    }

    /** The rent rule: the roundest whole-dirham term rent that divides back to the typed month. */
    @Test
    void rentFromMonthly_undoesTheRoundedDivision_andKeepsMeantFils() {
        assertThat(PortfolioImportPersistService.rentFromMonthly(new java.math.BigDecimal("8166.67"), 12))
                .isEqualByComparingTo("98000.00");
        assertThat(PortfolioImportPersistService.rentFromMonthly(new java.math.BigDecimal("5000"), 12))
                .isEqualByComparingTo("60000.00");
        assertThat(PortfolioImportPersistService.rentFromMonthly(new java.math.BigDecimal("8333.33"), 12))
                .isEqualByComparingTo("100000.00");
        assertThat(PortfolioImportPersistService.rentFromMonthly(new java.math.BigDecimal("1000.01"), 12))
                .isEqualByComparingTo("12000.12");
        assertThat(PortfolioImportPersistService.rentFromMonthly(new java.math.BigDecimal("5123.45"), 12))
                .isEqualByComparingTo("61481.40");
        assertThat(PortfolioImportPersistService.rentFromMonthly(new java.math.BigDecimal("4500"), 13))
                .isEqualByComparingTo("58500.00");
    }

    /** A booking cheque comes off the generated rows, first first; a row it empties is dropped. */
    @Test
    void takeBookingOff_reducesGeneratedRowsAndRefusesWhatItCannotPlace() {
        java.util.function.Function<String, ChequeRowInput> row = amt -> new ChequeRowInput(null, null,
                java.time.LocalDate.of(2026, 1, 1), null, java.time.LocalDate.of(2026, 1, 1), null, null, null,
                new java.math.BigDecimal(amt), "x", com.datagami.rentaxis.domain.entity.enums.ChequeMode.PDC);
        List<ChequeRowInput> rows = new java.util.ArrayList<>(List.of(row.apply("3800"), row.apply("1000")));
        assertThat(PortfolioImportPersistService.takeBookingOff(rows, new java.math.BigDecimal("4000"))).isNull();
        assertThat(rows).extracting(ChequeRowInput::amount)
                .usingElementComparator(java.math.BigDecimal::compareTo)
                .containsExactly(new java.math.BigDecimal("800"));

        List<ChequeRowInput> small = new java.util.ArrayList<>(List.of(row.apply("100")));
        assertThat(PortfolioImportPersistService.takeBookingOff(small, new java.math.BigDecimal("5000")))
                .contains("more than the rows it pays toward");
    }

    @Test
    void persist_monthlyRent_computesTotalCorrectly() {
        // 12-month lease, MonthlyRent=5000, no RentAmount.
        Workbook wb = buildWorkbookWithOneLease(b -> b
                .rentAmount("")
                .monthlyRent("5000")
                .startDate("2026-01-01")
                .endDate("2026-12-31"));

        service.persistWorkbook(wb, newJob());

        // End-date inclusive: Jan 1 → Dec 31 counts as 12 months; totalRent = 5000 * 12 = 60000.
        // The rent line carries that same total — the lease's own rentAmount is a
        // derived mirror of it, recomputed by syncDerivedTotals.
        assertThat(captureSavedLease().getRentAmount()).isEqualByComparingTo("60000");
        assertThat(lineOf(captureSavedLines(), "RENT").grossAmount()).isEqualByComparingTo("60000");
    }

    @Test
    void persist_rentAmount_isTakenAsTheTotal_independentOfPaymentTerms() {
        // A RentAmount column is the contract total for the term. paymentTerms says
        // how many instalments it is collected in and must not divide it.
        Workbook wb = buildWorkbookWithOneLease(b -> b
                .rentAmount("60000")
                .paymentTerms("4")
                .startDate("2026-01-01")
                .endDate("2026-12-31"));

        service.persistWorkbook(wb, newJob());

        assertThat(captureSavedLease().getRentAmount()).isEqualByComparingTo("60000");
        assertThat(lineOf(captureSavedLines(), "RENT").grossAmount()).isEqualByComparingTo("60000");
    }

    /**
     * The booking cheque is an instrument the renter handed over, so it becomes a
     * row on the register like any other — number, bank and date included. It used
     * to be read, validated, counted and then thrown away with a warning.
     */
    @Test
    void persist_bookingDeposit_becomesARegisterRow() throws Exception {
        Workbook wb = buildWorkbookWithOneLease(b -> b
                .bookingDeposit("10000", "BD-001", "2026-02-15", "Emirates NBD"));
        ImportJob job = newJob();

        service.persistWorkbook(wb, job);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChequeRowInput>> rows = ArgumentCaptor.forClass(List.class);
        verify(chequeGenerationService).saveRowsForSystemImport(any(Lease.class), rows.capture());
        // The booking cheque is its own row, last; the generated grid (one row of
        // 1,000,000 in this mock) is reduced by what it already paid.
        assertThat(rows.getValue()).hasSize(2);
        assertThat(rows.getValue().get(0).amount()).isEqualByComparingTo("990000");
        assertThat(rows.getValue().get(1)).satisfies(r -> {
            assertThat(r.chequeNumber()).isEqualTo("BD-001");
            assertThat(r.payeeBank()).isEqualTo("Emirates NBD");
            assertThat(r.chequeDate()).isEqualTo(java.time.LocalDate.of(2026, 2, 15));
            assertThat(r.amount()).isEqualByComparingTo("10000");
            assertThat(r.narration()).isEqualTo("Booking Deposit");
        });

        var details = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(job.getErrors(),
                        com.datagami.rentaxis.api.dto.PortfolioImportJobDetailsDTO.class);
        assertThat(details.getBookingDepositsCreated()).isEqualTo(1);
        assertThat(job.getSchedulesCreated()).isEqualTo(2);
        // Nothing was dropped, so nothing is warned about.
        assertThat(details.getWarnings() == null ? List.<com.datagami.rentaxis.api.dto.ImportErrorDTO>of()
                : details.getWarnings())
                .extracting(com.datagami.rentaxis.api.dto.ImportErrorDTO::getMessage)
                .noneSatisfy(m -> assertThat(m).contains("not imported"));
    }

    @Test
    void persist_rentVatDefaultsFromCommercialProperty_whenToggleBlank() {
        Workbook wb = buildWorkbookWithOneLease(b -> b
                .propertyType("COMMERCIAL")
                .adminFee("500").parkingRemoteFee("100")
                .rentVat("").adminVat("").depositVat("").parkingVat(""));

        service.persistWorkbook(wb, newJob());

        Lease saved = captureSavedLease();
        assertThat(saved.isRentVatApplicable()).isTrue();
        // Fee VAT defaults to the commercial flag when the per-fee VAT column is
        // blank. The deposit never carries VAT, whatever the property type.
        List<LeaseLineInput> lines = captureSavedLines();
        assertThat(lineOf(lines, "RENT").vatApplicable()).isTrue();
        assertThat(lineOf(lines, "ADMIN_FEE").vatApplicable()).isTrue();
        assertThat(lineOf(lines, "PARKING_FEE").vatApplicable()).isTrue();
        assertThat(lineOf(lines, "SECURITY_DEPOSIT").vatApplicable()).isFalse();
    }

    @Test
    void persist_rentVatDefaultsFalse_forResidentialProperty_whenToggleBlank() {
        Workbook wb = buildWorkbookWithOneLease(b -> b
                .propertyType("RESIDENTIAL")
                .adminFee("500").parkingRemoteFee("100")
                .rentVat("").adminVat("").depositVat("").parkingVat(""));

        service.persistWorkbook(wb, newJob());

        Lease saved = captureSavedLease();
        assertThat(saved.isRentVatApplicable()).isFalse();
        assertThat(captureSavedLines()).allSatisfy(l -> assertThat(l.vatApplicable()).isFalse());
    }

    @Test
    void persist_noChequesSheet_keepsTheSheetsPaymentTerms() {
        Workbook wb = buildWorkbookWithOneLease(b -> b.paymentTerms("4"));

        service.persistWorkbook(wb, newJob());

        // Nothing generates a plan at import time any more; the lease simply says
        // how many instalments it is to be collected in.
        assertThat(captureSavedLease().getPaymentTerms()).isEqualTo(4);
    }

    @Test
    void persist_chequesSheet_overridesPaymentTerms() {
        Workbook wb = buildWorkbookWithOneLease(b -> b.paymentTerms("4"));
        addChequesSheet(wb,
                cheque("Marina Heights", "101", "ahmed@email.com",
                        "1", "2026-01-01", "2026-01-01", "C-1", "Emirates NBD", "12000", "CHEQUE"),
                cheque("Marina Heights", "101", "ahmed@email.com",
                        "2", "2026-04-01", "2026-04-01", "C-2", "Emirates NBD", "12000", "CHEQUE"),
                cheque("Marina Heights", "101", "ahmed@email.com",
                        "3", "2026-07-01", "2026-07-01", "C-3", "Emirates NBD", "12000", "CHEQUE"),
                cheque("Marina Heights", "101", "ahmed@email.com",
                        "4", "2026-10-01", "2026-10-01", "C-4", "Emirates NBD", "12000", "CHEQUE"),
                cheque("Marina Heights", "101", "ahmed@email.com",
                        "5", "2026-12-01", "2026-12-01", "C-5", "Emirates NBD", "12000", "CHEQUE"));

        service.persistWorkbook(wb, newJob());

        // A Cheques sheet fixes the instalment count — 5, not the workbook's 4 —
        // which is the one thing the cheque generator (Task 5) needs from it. The
        // rows themselves are no longer materialised as payment schedules.
        ArgumentCaptor<Lease> leaseCap = ArgumentCaptor.forClass(Lease.class);
        verify(leaseRepository, atLeastOnce()).save(leaseCap.capture());
        Lease lastSaved = leaseCap.getAllValues().get(leaseCap.getAllValues().size() - 1);
        assertThat(lastSaved.getPaymentTerms()).isEqualTo(5);
    }

    /**
     * The Cheques sheet is a statement of the instruments the renter handed over,
     * so every column of it lands on the register: the date on the paper, its
     * number, its bank, and the mode the Method column names.
     */
    @Test
    void persist_chequesSheet_becomesTheLeasesGrid() throws Exception {
        Workbook wb = buildWorkbookWithOneLease(b -> b
                .paymentTerms("3")
                .startDate("2026-01-01").endDate("2026-12-31"));
        addChequesSheet(wb,
                cheque("Marina Heights", "101", "ahmed@email.com",
                        "1", "2026-01-01", "2026-01-01", "C-1", "Emirates NBD", "20000", "CHEQUE"),
                cheque("Marina Heights", "101", "ahmed@email.com",
                        "2", "2026-05-01", "", "", "", "20000", "CASH"),
                cheque("Marina Heights", "101", "ahmed@email.com",
                        "3", "2026-09-01", "2026-09-01", "TXN-99", "Mashreq", "20000", "BANK_TRANSFER"));
        ImportJob job = newJob();

        service.persistWorkbook(wb, job);

        var details = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(job.getErrors(),
                        com.datagami.rentaxis.api.dto.PortfolioImportJobDetailsDTO.class);
        assertThat(details.getChequesFromSheet()).isEqualTo(3);
        assertThat(job.getSchedulesCreated()).isEqualTo(3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChequeRowInput>> rows = ArgumentCaptor.forClass(List.class);
        verify(chequeGenerationService).saveRowsForSystemImport(any(Lease.class), rows.capture());
        assertThat(rows.getValue()).hasSize(3);
        assertThat(rows.getValue()).extracting(ChequeRowInput::mode)
                .containsExactly(ChequeMode.PDC, ChequeMode.CASH, ChequeMode.TRANSFER);
        // ChequeOrPaymentDate when the sheet gives one, else the instalment's DueDate.
        assertThat(rows.getValue()).extracting(ChequeRowInput::chequeDate)
                .containsExactly(java.time.LocalDate.of(2026, 1, 1),
                        java.time.LocalDate.of(2026, 5, 1),
                        java.time.LocalDate.of(2026, 9, 1));
        // A cheque number is a PDC's; a cash row has none and a transfer's
        // reference is not one, which ChequeRowRules would refuse outright.
        assertThat(rows.getValue()).extracting(ChequeRowInput::chequeNumber)
                .containsExactly("C-1", null, null);
        // Nothing is generated when the sheet says what the instruments are.
        verify(chequeGenerationService, never()).generateForSystemImport(any(), any());
    }

    /**
     * A sheet row the shared rules refuse is an import error, not an exception
     * that kills the workbook: the admin gets the rest of their portfolio and a
     * line telling them which lease to fix.
     */
    @Test
    void persist_chequesSheetRowBreakingTheRowRules_isReportedAndTheGridIsSkipped() throws Exception {
        Workbook wb = buildWorkbookWithOneLease(b -> b
                .paymentTerms("2")
                .startDate("2026-01-01").endDate("2026-12-31"));
        addChequesSheet(wb,
                cheque("Marina Heights", "101", "ahmed@email.com",
                        "1", "2026-01-01", "2026-01-01", "C-1", "Emirates NBD", "20000", "CHEQUE"),
                // The same cheque number twice on one lease.
                cheque("Marina Heights", "101", "ahmed@email.com",
                        "2", "2026-07-01", "2026-07-01", "C-1", "Emirates NBD", "20000", "CHEQUE"));
        ImportJob job = newJob();

        service.persistWorkbook(wb, job);

        verify(chequeGenerationService, never()).saveRowsForSystemImport(any(), any());
        var details = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(job.getErrors(),
                        com.datagami.rentaxis.api.dto.PortfolioImportJobDetailsDTO.class);
        assertThat(details.getErrors())
                .extracting(com.datagami.rentaxis.api.dto.ImportErrorDTO::getMessage)
                .anySatisfy(m -> assertThat(m).contains("C-1"));
        // The lease itself still landed.
        assertThat(job.getLeasesCreated()).isEqualTo(1);
    }

    @Test
    void persist_feeColumns_becomeTheirOwnLinesAlongsideRent() {
        // The admin fee used to be a one-time charge schedule row beside the rent
        // instalments. It is a charge line now — same money, one representation.
        Workbook wb = buildWorkbookWithOneLease(b -> b
                .adminFee("500"));
        addChequesSheet(wb,
                cheque("Marina Heights", "101", "ahmed@email.com",
                        "1", "2026-01-01", "2026-01-01", "C-1", "Emirates NBD", "30000", "CHEQUE"),
                cheque("Marina Heights", "101", "ahmed@email.com",
                        "2", "2026-07-01", "2026-07-01", "C-2", "Emirates NBD", "30000", "CHEQUE"));

        service.persistWorkbook(wb, newJob());

        List<LeaseLineInput> lines = captureSavedLines();
        // Residential property, blank VAT column → no VAT on the fee.
        assertThat(lineOf(lines, "ADMIN_FEE").grossAmount()).isEqualByComparingTo("500");
        assertThat(lineOf(lines, "ADMIN_FEE").vatApplicable()).isFalse();
        assertThat(lines).extracting(LeaseLineInput::chargeTypeCode).contains("RENT");
    }

    @Test
    void persist_withWarnings_foldsThemIntoJobErrorsWrapper() throws Exception {
        Workbook wb = buildWorkbookWithOneLease(b -> b.paymentTerms("4"));
        ImportJob job = newJob();

        var warnings = List.of(
                new com.datagami.rentaxis.api.dto.ImportErrorDTO(
                        "Cheques", 7, "DueDate",
                        "DueDate 2027-01-01 is outside lease period 2026-01-01..2026-12-31"));

        service.persistWorkbook(wb, job, warnings);

        assertThat(job.getErrors())
                .as("warnings must be folded into the wrapper even when no counters are set")
                .isNotNull()
                .startsWith("{");
        var details = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(job.getErrors(),
                        com.datagami.rentaxis.api.dto.PortfolioImportJobDetailsDTO.class);
        assertThat(details.getWarnings()).hasSize(1);
        assertThat(details.getWarnings().get(0).getField()).isEqualTo("DueDate");
    }

    @Test
    void persist_writesCountersIntoJobErrorsColumnAsWrapperJson() throws Exception {
        Workbook wb = buildWorkbookWithOneLease(b -> b
                .bookingDeposit("10000", "BD-001", "2026-02-15", "Emirates NBD"));
        addChequesSheet(wb,
                cheque("Marina Heights", "101", "ahmed@email.com",
                        "1", "2026-01-01", "2026-01-01", "C-1", "Emirates NBD", "60000", "CHEQUE"));

        ImportJob job = newJob();
        service.persistWorkbook(wb, job);

        assertThat(job.getErrors())
                .as("counters must be persisted into the JSONB errors column as a wrapper object")
                .isNotNull()
                .startsWith("{");
        var details = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(job.getErrors(),
                        com.datagami.rentaxis.api.dto.PortfolioImportJobDetailsDTO.class);
        assertThat(details.getChequesFromSheet()).isEqualTo(1);
        assertThat(details.getBookingDepositsCreated()).isEqualTo(1);
    }

    @Test
    void persist_legacyTenColumnWorkbook_persistsActiveLeaseUnchanged() {
        // Regression: the legacy 10-column path (no Status column) still asks for an
        // ACTIVE lease — written DRAFT and queued for the post phase (gap #83).
        Workbook wb = buildLegacyOneLeaseWorkbook();

        PortfolioImportPersistService.PersistResult result = service.persistWorkbook(wb, newJob());

        Lease saved = captureSavedLease();
        assertThat(saved.getStatus()).isEqualTo(LeaseStatus.DRAFT);
        assertThat(result.toPost()).hasSize(1);
        assertThat(saved.getEjariNumber()).isEqualTo("EJ-2026-001");
    }

    // ----- Helpers -----

    /**
     * The last state the lease was saved in. A lease is now saved more than once —
     * once to get an id, again to stamp its own chain id, again after its derived
     * totals are recomputed — so this takes the final value rather than insisting
     * on a single save.
     */
    private Lease captureSavedLease() {
        ArgumentCaptor<Lease> cap = ArgumentCaptor.forClass(Lease.class);
        verify(leaseRepository, atLeastOnce()).save(cap.capture());
        List<Lease> all = cap.getAllValues();
        return all.get(all.size() - 1);
    }

    private Unit captureLastSavedUnit() {
        ArgumentCaptor<Unit> cap = ArgumentCaptor.forClass(Unit.class);
        verify(unitRepository, atLeastOnce()).save(cap.capture());
        List<Unit> all = cap.getAllValues();
        return all.get(all.size() - 1);
    }

    private static ImportJob newJob() {
        ImportJob job = new ImportJob();
        job.setId(UUID.randomUUID());
        return job;
    }

    /** Mutable builder for the single-lease test workbook. */
    static class LeaseRowBuilder {
        String propertyName = "Marina Heights";
        String propertyType = "RESIDENTIAL";
        String buildingName = "Tower A";
        String unitNumber = "101";
        String renterEmail = "ahmed@email.com";
        String startDate = "2026-01-01";
        String endDate = "2026-12-31";
        String rentAmount = "60000";
        String monthlyRent = "";
        String depositAmount = "5000";
        String paymentTerms = "12";
        String paymentMethod = "CHEQUE";
        String ejariNumber = "EJ-2026-001";
        String adminFee = "";
        String parkingRemoteFee = "";
        String rentVat = "";
        String adminVat = "";
        String depositVat = "";
        String parkingVat = "";
        String depositPaymentMethod = "";
        String agreementDate = "";
        String status = "ACTIVE";
        String bdAmount = "";
        String bdNumber = "";
        String bdDate = "";
        String bdBank = "";

        LeaseRowBuilder propertyType(String v) { propertyType = v; return this; }
        LeaseRowBuilder startDate(String v) { startDate = v; return this; }
        LeaseRowBuilder endDate(String v) { endDate = v; return this; }
        LeaseRowBuilder rentAmount(String v) { rentAmount = v; return this; }
        LeaseRowBuilder monthlyRent(String v) { monthlyRent = v; return this; }
        LeaseRowBuilder paymentTerms(String v) { paymentTerms = v; return this; }
        LeaseRowBuilder paymentMethod(String v) { paymentMethod = v; return this; }
        LeaseRowBuilder adminFee(String v) { adminFee = v; return this; }
        LeaseRowBuilder parkingRemoteFee(String v) { parkingRemoteFee = v; return this; }
        LeaseRowBuilder rentVat(String v) { rentVat = v; return this; }
        LeaseRowBuilder adminVat(String v) { adminVat = v; return this; }
        LeaseRowBuilder depositVat(String v) { depositVat = v; return this; }
        LeaseRowBuilder parkingVat(String v) { parkingVat = v; return this; }
        LeaseRowBuilder depositPaymentMethod(String v) { depositPaymentMethod = v; return this; }
        LeaseRowBuilder agreementDate(String v) { agreementDate = v; return this; }
        LeaseRowBuilder status(String v) { status = v; return this; }
        LeaseRowBuilder bookingDeposit(String amount, String number, String date, String bank) {
            this.bdAmount = amount; this.bdNumber = number; this.bdDate = date; this.bdBank = bank;
            return this;
        }
    }

    static Workbook buildWorkbookWithOneLease(Consumer<LeaseRowBuilder> mutate) {
        LeaseRowBuilder b = new LeaseRowBuilder();
        mutate.accept(b);

        Workbook wb = new XSSFWorkbook();

        Sheet props = wb.createSheet("Properties");
        writeRow(props, 0, "PropertyName", "PropertyNameAr", "Emirate", "Address", "Type", "MakaniNumber");
        writeRow(props, 1, b.propertyName, "", "DUBAI", "Dubai Marina", b.propertyType, "");

        Sheet units = wb.createSheet("Units");
        writeRow(units, 0, "PropertyName", "BuildingName", "UnitNumber", "UnitType", "SizeSqft", "ExpectedRent");
        writeRow(units, 1, b.propertyName, b.buildingName, b.unitNumber, "BHK1", "850", "60000");

        Sheet renters = wb.createSheet("Renters");
        writeRow(renters, 0, "Name", "NameAr", "Email", "Phone");
        writeRow(renters, 1, "Ahmed Ali", "", b.renterEmail, "");

        Sheet leases = wb.createSheet("Leases");
        writeRow(leases, 0,
                "PropertyName", "BuildingName", "UnitNumber", "RenterEmail",
                "StartDate", "EndDate",
                "RentAmount", "DepositAmount", "PaymentTerms", "PaymentMethod", "EjariNumber",
                "MonthlyRent",
                "AdminFee", "ParkingRemoteFee",
                "RentVatApplicable", "AdminFeeVatApplicable",
                "SecurityDepositVatApplicable", "ParkingRemoteVatApplicable",
                "DepositPaymentMethod", "AgreementDate", "Status",
                "BookingDeposit_Amount", "BookingDeposit_Number", "BookingDeposit_Date", "BookingDeposit_Bank");
        writeRow(leases, 1,
                b.propertyName, b.buildingName, b.unitNumber, b.renterEmail,
                b.startDate, b.endDate,
                b.rentAmount, b.depositAmount, b.paymentTerms, b.paymentMethod, b.ejariNumber,
                b.monthlyRent,
                b.adminFee, b.parkingRemoteFee,
                b.rentVat, b.adminVat,
                b.depositVat, b.parkingVat,
                b.depositPaymentMethod, b.agreementDate, b.status,
                b.bdAmount, b.bdNumber, b.bdDate, b.bdBank);
        return wb;
    }

    static Workbook buildLegacyOneLeaseWorkbook() {
        Workbook wb = new XSSFWorkbook();

        Sheet props = wb.createSheet("Properties");
        writeRow(props, 0, "PropertyName", "PropertyNameAr", "Emirate", "Address", "Type", "MakaniNumber");
        writeRow(props, 1, "Marina Heights", "", "DUBAI", "Dubai Marina", "RESIDENTIAL", "");

        Sheet units = wb.createSheet("Units");
        writeRow(units, 0, "PropertyName", "BuildingName", "UnitNumber", "UnitType", "SizeSqft", "ExpectedRent");
        writeRow(units, 1, "Marina Heights", "Tower A", "101", "BHK1", "850", "60000");

        Sheet renters = wb.createSheet("Renters");
        writeRow(renters, 0, "Name", "NameAr", "Email", "Phone");
        writeRow(renters, 1, "Ahmed Ali", "", "ahmed@email.com", "");

        Sheet leases = wb.createSheet("Leases");
        writeRow(leases, 0,
                "PropertyName", "BuildingName", "UnitNumber", "RenterEmail",
                "StartDate", "EndDate",
                "RentAmount", "DepositAmount", "PaymentTerms", "PaymentMethod", "EjariNumber");
        writeRow(leases, 1,
                "Marina Heights", "Tower A", "101", "ahmed@email.com",
                "2026-01-01", "2026-12-31",
                "60000", "5000", "12", "CHEQUE", "EJ-2026-001");
        return wb;
    }

    static void writeRow(Sheet sheet, int rowIdx, String... values) {
        Row row = sheet.createRow(rowIdx);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i] == null ? "" : values[i]);
        }
    }

    /** Compact cheque-row tuple for test fixtures. */
    record ChequeRow(String propertyName, String unitNumber, String renterEmail,
                     String installmentNo, String dueDate, String chequeOrPaymentDate,
                     String uniqueId, String bank, String amount, String method) {}

    static ChequeRow cheque(String propertyName, String unitNumber, String renterEmail,
                            String installmentNo, String dueDate, String chequeOrPaymentDate,
                            String uniqueId, String bank, String amount, String method) {
        return new ChequeRow(propertyName, unitNumber, renterEmail, installmentNo, dueDate,
                chequeOrPaymentDate, uniqueId, bank, amount, method);
    }

    static void addChequesSheet(Workbook wb, ChequeRow... rows) {
        Sheet sheet = wb.createSheet("Cheques");
        writeRow(sheet, 0,
                "PropertyName", "UnitNumber", "RenterEmail",
                "InstallmentNo", "DueDate", "ChequeOrPaymentDate",
                "UniqueId", "Bank", "Amount", "Method");
        for (int i = 0; i < rows.length; i++) {
            ChequeRow r = rows[i];
            writeRow(sheet, i + 1,
                    r.propertyName, r.unitNumber, r.renterEmail,
                    r.installmentNo, r.dueDate, r.chequeOrPaymentDate,
                    r.uniqueId, r.bank, r.amount, r.method);
        }
    }
}
