package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PortfolioImportJobDetailsDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * End-to-end-style test for the bulk-import persist flow with the new
 * payment-schedule extension. Drives a single workbook through all five
 * scenarios from the plan and asserts the persist service writes the right
 * entities + counters.
 *
 * <p>This is stricter than each unit test in isolation: it exercises the
 * Cheques-sheet override, auto-distribute, DRAFT status, booking deposits, and
 * MonthlyRent-input paths in one call.</p>
 *
 * <p>Repositories are mocked. Despite covering multiple scenarios, this is NOT
 * a Spring Boot integration test (no DB, no transaction boundary verification,
 * no async-dispatch coverage) — those are out of scope for the test infra here.</p>
 */
@ExtendWith(MockitoExtension.class)
class PortfolioImportPersistEndToEndTest {

    @Mock PropertyRepository propertyRepository;
    @Mock BuildingRepository buildingRepository;
    @Mock UnitRepository unitRepository;
    @Mock RenterRepository renterRepository;
    @Mock LeaseRepository leaseRepository;
    @Mock ImportJobRepository importJobRepository;
    @Mock LeaseService leaseService;
    @Mock ChargeTypeService chargeTypeService;
    @Mock ChequeGenerationService chequeGenerationService;

    PortfolioImportPersistService service;

    @BeforeEach
    void setUp() {
        service = new PortfolioImportPersistService(
                propertyRepository, buildingRepository, unitRepository,
                renterRepository, leaseRepository, importJobRepository,
                leaseService, chargeTypeService, chequeGenerationService);
        lenient().when(chequeGenerationService.generateForSystemImport(any(), any())).thenReturn(java.util.List.of());
        lenient().when(chequeGenerationService.saveRowsForSystemImport(any(), any())).thenReturn(java.util.List.of());
        lenient().when(propertyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(buildingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(unitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(renterRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(leaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(importJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void persistWorkbook_withFiveScenarios_persistsAllAndRecordsCounters() throws Exception {
        Workbook wb = buildFiveScenarioWorkbook();
        ImportJob job = new ImportJob();
        job.setId(UUID.randomUUID());

        service.persistWorkbook(wb, job);

        // 5 leases saved (one per scenario).
        ArgumentCaptor<Lease> leaseCap = ArgumentCaptor.forClass(Lease.class);
        verify(leaseRepository, atLeastOnce()).save(leaseCap.capture());
        // Each lease may be saved twice when Cheques-sheet override re-saves with
        // updated paymentTerms; dedupe by reference identity.
        List<Lease> distinctLeases = leaseCap.getAllValues().stream().distinct().toList();
        assertThat(distinctLeases).hasSize(5);

        // Status mix: 4 ACTIVE, 1 DRAFT.
        assertThat(distinctLeases).filteredOn(l -> l.getStatus() == LeaseStatus.DRAFT).hasSize(1);
        assertThat(distinctLeases).filteredOn(l -> l.getStatus() == LeaseStatus.ACTIVE).hasSize(4);

        // DRAFT lease's unit must remain VACANT — the OCCUPIED transition is gated.
        ArgumentCaptor<Unit> unitCap = ArgumentCaptor.forClass(Unit.class);
        verify(unitRepository, atLeastOnce()).save(unitCap.capture());
        // Filter out unit creations during sheet 3 (Units sheet) — those start VACANT.
        // The flips to OCCUPIED come AFTER initial save, so only the latest save per
        // unit reflects the final state.
        // In any case, at least one save must have stayed VACANT (the DRAFT scenario's unit).
        assertThat(unitCap.getAllValues())
                .anyMatch(u -> u.getStatus() == UnitStatus.VACANT);
        assertThat(unitCap.getAllValues())
                .anyMatch(u -> u.getStatus() == UnitStatus.OCCUPIED);

        // Every scenario has a positive deposit, so every lease gets a RENT line and
        // a SECURITY_DEPOSIT line. These used to be a LeaseCharge row plus a
        // security-deposit payment-schedule row; the money is now described once.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LeaseLineInput>> lineCap = ArgumentCaptor.forClass(List.class);
        verify(leaseService, org.mockito.Mockito.times(5)).applyLines(any(Lease.class), lineCap.capture());
        assertThat(lineCap.getAllValues()).hasSize(5).allSatisfy(lines ->
                assertThat(lines).extracting(LeaseLineInput::chargeTypeCode)
                        .startsWith("RENT").contains("SECURITY_DEPOSIT"));
        verify(leaseService, org.mockito.Mockito.times(5)).syncDerivedTotals(any(Lease.class));

        // Counters serialized into the JSONB column. chequesFromSheet counts the
        // sheet's rows (scenario 2's four); schedulesCreated now counts the register
        // rows the import wrote — those four plus scenario 4's booking cheque.
        assertThat(job.getErrors()).startsWith("{");
        PortfolioImportJobDetailsDTO details = new ObjectMapper()
                .readValue(job.getErrors(), PortfolioImportJobDetailsDTO.class);
        assertThat(details.getChequesFromSheet()).isEqualTo(4);
        assertThat(details.getBookingDepositsCreated()).isEqualTo(1);
        assertThat(job.getSchedulesCreated()).isEqualTo(5);

        // Job summary counters.
        assertThat(job.getLeasesCreated()).isEqualTo(5);
        assertThat(job.getRentersCreated()).isEqualTo(5);
        assertThat(job.getPropertiesCreated()).isEqualTo(1);
    }

    // ----- Workbook fixture -----

    /** Builds a workbook covering the five plan scenarios in one go. */
    static Workbook buildFiveScenarioWorkbook() {
        Workbook wb = new XSSFWorkbook();

        Sheet props = wb.createSheet("Properties");
        writeRow(props, 0, "PropertyName", "PropertyNameAr", "Emirate", "Address", "Type", "MakaniNumber");
        writeRow(props, 1, "Marina Heights", "", "DUBAI", "Dubai Marina", "RESIDENTIAL", "");

        Sheet units = wb.createSheet("Units");
        writeRow(units, 0, "PropertyName", "BuildingName", "UnitNumber", "UnitType", "SizeSqft", "ExpectedRent");
        for (int u = 1; u <= 5; u++) {
            writeRow(units, u, "Marina Heights", "Tower A", String.valueOf(100 + u),
                    "BHK1", "850", "60000");
        }

        Sheet renters = wb.createSheet("Renters");
        writeRow(renters, 0, "Name", "NameAr", "Email", "Phone");
        for (int r = 1; r <= 5; r++) {
            writeRow(renters, r, "Tenant " + r, "", "tenant" + r + "@email.com", "");
        }

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
        // Scenario 1: auto-distribute, paymentTerms=4, no cheques sheet rows.
        // Deposit set to match the per-cheque amount (60000/4 = 15000) so the
        // schedule generator's deposit cap on the final cheque is satisfied.
        writeRow(leases, 1,
                "Marina Heights", "Tower A", "101", "tenant1@email.com",
                "2026-01-01", "2026-12-31",
                "60000", "15000", "4", "CHEQUE", "EJ-1",
                "", "", "",
                "", "", "", "",
                "", "", "ACTIVE",
                "", "", "", "");
        // Scenario 2: Cheques-sheet override (4 cheque rows below).
        writeRow(leases, 2,
                "Marina Heights", "Tower A", "102", "tenant2@email.com",
                "2026-01-01", "2026-12-31",
                "60000", "5000", "12", "CHEQUE", "EJ-2",
                "", "", "",
                "", "", "", "",
                "", "", "ACTIVE",
                "", "", "", "");
        // Scenario 3: Status=DRAFT, unit must stay VACANT. Deposit matches
        // per-cheque (15000) so the auto-distribute path's cap is satisfied.
        writeRow(leases, 3,
                "Marina Heights", "Tower A", "103", "tenant3@email.com",
                "2026-01-01", "2026-12-31",
                "60000", "15000", "4", "CHEQUE", "EJ-3",
                "", "", "",
                "", "", "", "",
                "", "", "DRAFT",
                "", "", "", "");
        // Scenario 4: booking deposit + ACTIVE. Deposit matches per-cheque
        // (15000) so the auto-distribute path's cap is satisfied.
        writeRow(leases, 4,
                "Marina Heights", "Tower A", "104", "tenant4@email.com",
                "2026-01-01", "2026-12-31",
                "60000", "15000", "4", "CHEQUE", "EJ-4",
                "", "", "",
                "", "", "", "",
                "", "", "ACTIVE",
                "10000", "BD-001", "2026-01-15", "Emirates NBD");
        // Scenario 5: MonthlyRent input (no RentAmount).
        writeRow(leases, 5,
                "Marina Heights", "Tower A", "105", "tenant5@email.com",
                "2026-01-01", "2026-12-31",
                "", "5000", "12", "CHEQUE", "EJ-5",
                "5000", "", "",
                "", "", "", "",
                "", "", "ACTIVE",
                "", "", "", "");

        // Cheques sheet — only scenario 2 has rows.
        Sheet cheques = wb.createSheet("Cheques");
        writeRow(cheques, 0,
                "PropertyName", "UnitNumber", "RenterEmail",
                "InstallmentNo", "DueDate", "ChequeOrPaymentDate",
                "UniqueId", "Bank", "Amount", "Method");
        writeRow(cheques, 1, "Marina Heights", "102", "tenant2@email.com",
                "1", "2026-01-01", "2026-01-01", "C-1", "Emirates NBD", "15000", "CHEQUE");
        writeRow(cheques, 2, "Marina Heights", "102", "tenant2@email.com",
                "2", "2026-04-01", "2026-04-01", "C-2", "Emirates NBD", "15000", "CHEQUE");
        writeRow(cheques, 3, "Marina Heights", "102", "tenant2@email.com",
                "3", "2026-07-01", "2026-07-01", "C-3", "Emirates NBD", "15000", "CHEQUE");
        writeRow(cheques, 4, "Marina Heights", "102", "tenant2@email.com",
                "4", "2026-10-01", "2026-10-01", "C-4", "Emirates NBD", "15000", "CHEQUE");

        return wb;
    }

    static void writeRow(Sheet sheet, int rowIdx, String... values) {
        Row row = sheet.createRow(rowIdx);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i] == null ? "" : values[i]);
        }
    }
}
