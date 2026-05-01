package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    @Mock PaymentScheduleService paymentScheduleService;
    @Mock PaymentScheduleRepository paymentScheduleRepository;
    @Mock ImportJobRepository importJobRepository;

    PortfolioImportPersistService service;

    @BeforeEach
    void setUp() {
        service = new PortfolioImportPersistService(
                propertyRepository, buildingRepository, unitRepository,
                renterRepository, leaseRepository, paymentScheduleService,
                paymentScheduleRepository, importJobRepository);

        // save(...) → return the input entity, simulating ID assignment.
        lenient().when(propertyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(buildingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(unitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(renterRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(leaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(paymentScheduleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(importJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(paymentScheduleService.generateScheduleForLease(any()))
                .thenReturn(Collections.emptyList());
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
    void persist_draftStatus_keepsUnitVacant() {
        Workbook wb = buildWorkbookWithOneLease(b -> b.status("DRAFT"));

        service.persistWorkbook(wb, newJob());

        Unit unit = captureLastSavedUnit();
        assertThat(unit.getStatus())
                .as("DRAFT lease must NOT flip the unit to OCCUPIED")
                .isEqualTo(UnitStatus.VACANT);
    }

    @Test
    void persist_activeStatus_setsUnitOccupied() {
        Workbook wb = buildWorkbookWithOneLease(b -> b.status("ACTIVE"));

        service.persistWorkbook(wb, newJob());

        Unit unit = captureLastSavedUnit();
        assertThat(unit.getStatus()).isEqualTo(UnitStatus.OCCUPIED);
    }

    @Test
    void persist_blankStatus_defaultsToActive() {
        Workbook wb = buildWorkbookWithOneLease(b -> b.status(""));

        service.persistWorkbook(wb, newJob());

        Lease saved = captureSavedLease();
        assertThat(saved.getStatus()).isEqualTo(LeaseStatus.ACTIVE);
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

        Lease saved = captureSavedLease();
        assertThat(saved.getMonthlyRent()).isEqualByComparingTo("5000");
        // monthsBetween(2026-01-01, 2026-12-31) == 11; totalRent = 5000 * 11 = 55000.
        assertThat(saved.getRentAmount()).isEqualByComparingTo("55000");
    }

    @Test
    void persist_rentAmount_computesMonthlyCorrectly_independentOfPaymentTerms() {
        // BUG FIX: monthly should be RentAmount / months, NOT RentAmount / paymentTerms.
        Workbook wb = buildWorkbookWithOneLease(b -> b
                .rentAmount("60000")
                .paymentTerms("4")
                .startDate("2026-01-01")
                .endDate("2026-12-31"));

        service.persistWorkbook(wb, newJob());

        Lease saved = captureSavedLease();
        // monthsBetween(2026-01-01, 2026-12-31) == 11; monthlyRent = 60000 / 11 = 5454.55.
        assertThat(saved.getRentAmount()).isEqualByComparingTo("60000");
        assertThat(saved.getMonthlyRent())
                .as("monthly rent must be totalRent/months, NOT totalRent/paymentTerms")
                .isEqualByComparingTo("5454.55");
    }

    @Test
    void persist_bookingDeposit_savesBookingPaymentScheduleRow() {
        Workbook wb = buildWorkbookWithOneLease(b -> b
                .bookingDeposit("10000", "BD-001", "2026-02-15", "Emirates NBD"));

        service.persistWorkbook(wb, newJob());

        ArgumentCaptor<PaymentSchedule> cap = ArgumentCaptor.forClass(PaymentSchedule.class);
        verify(paymentScheduleRepository, atLeastOnce()).save(cap.capture());
        PaymentSchedule booking = cap.getAllValues().stream()
                .filter(PaymentSchedule::isBookingDeposit)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a booking-deposit row to be saved"));
        assertThat(booking.getAmount()).isEqualByComparingTo("10000");
        assertThat(booking.getChequeNumber()).isEqualTo("BD-001");
        assertThat(booking.getChequeDate()).isEqualTo(LocalDate.parse("2026-02-15"));
        assertThat(booking.getBankName()).isEqualTo("Emirates NBD");
        assertThat(booking.getPurposeLabel()).isEqualTo("BOOKING RECEIVED");
    }

    @Test
    void persist_vatDefaultsFromCommercialProperty_whenTogglesBlank() {
        Workbook wb = buildWorkbookWithOneLease(b -> b
                .propertyType("COMMERCIAL")
                .rentVat("").adminVat("").depositVat("").parkingVat(""));

        service.persistWorkbook(wb, newJob());

        Lease saved = captureSavedLease();
        assertThat(saved.isRentVatApplicable()).isTrue();
        assertThat(saved.isAdminFeeVatApplicable()).isTrue();
        assertThat(saved.isSecurityDepositVatApplicable()).isTrue();
        assertThat(saved.isParkingRemoteVatApplicable()).isTrue();
    }

    @Test
    void persist_vatDefaultsFalse_forResidentialProperty_whenTogglesBlank() {
        Workbook wb = buildWorkbookWithOneLease(b -> b
                .propertyType("RESIDENTIAL")
                .rentVat("").adminVat("").depositVat("").parkingVat(""));

        service.persistWorkbook(wb, newJob());

        Lease saved = captureSavedLease();
        assertThat(saved.isRentVatApplicable()).isFalse();
        assertThat(saved.isAdminFeeVatApplicable()).isFalse();
        assertThat(saved.isSecurityDepositVatApplicable()).isFalse();
        assertThat(saved.isParkingRemoteVatApplicable()).isFalse();
    }

    @Test
    void persist_legacyTenColumnWorkbook_persistsActiveLeaseUnchanged() {
        // Regression: verify the legacy 10-column path still produces an ACTIVE lease.
        Workbook wb = buildLegacyOneLeaseWorkbook();

        service.persistWorkbook(wb, newJob());

        Lease saved = captureSavedLease();
        assertThat(saved.getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(saved.getEjariNumber()).isEqualTo("EJ-2026-001");
    }

    // ----- Helpers -----

    private Lease captureSavedLease() {
        ArgumentCaptor<Lease> cap = ArgumentCaptor.forClass(Lease.class);
        verify(leaseRepository).save(cap.capture());
        return cap.getValue();
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
}
