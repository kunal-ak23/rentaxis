package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PortfolioImportJobDetailsDTO;
import com.datagami.rentaxis.api.dto.ledger.RoleMappingDTO;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.ImportJob;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.ImportJobRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gap #83, end to end: a portfolio workbook imported through the real async door
 * puts its ACTIVE rows <em>on the books</em>.
 *
 * <p>The workbook is the Al Waha Towers file from the live walk (clean variant):
 * one property, six units, four renters, three ACTIVE leases — 101 and 201 with a
 * Cheques sheet that states only the rent instalments, 301 with no sheet and a
 * MonthlyRent of 8,166.67 — and a DRAFT lease with a booking cheque. Before the
 * fix the three ACTIVE leases arrived ACTIVE with no TCO, DRAFT cheques covering
 * rent only, and a property with no ledger accounts.</p>
 */
@SpringBootTest
class PortfolioImportPostingIT extends AbstractPostgresIT {

    @Autowired PortfolioImportService importService;
    @Autowired ImportJobRepository importJobs;
    @Autowired LandlordOrgRepository orgs;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired PropertyRepository properties;
    @Autowired LeaseRepository leases;
    @Autowired LeaseLineRepository leaseLines;
    @Autowired ChequeRepository cheques;
    @Autowired JournalEntryRepository journals;
    @Autowired UnitRepository units;
    @Autowired LedgerQueryService ledger;
    @Autowired LeasePostingService leasePosting;
    @Autowired TransactionTemplate tx;

    private UUID tenantId;
    private Authentication admin;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("IT-AlWaha-" + UUID.randomUUID());
        tenantId = orgs.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        admin = new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(admin);
        // The chart and the per-property template a fresh organisation gets.
        accounts.seedDefaultAccounts();
        propertyAccounts.seedDefaultTemplateAndDefaults();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test
    void activeRowsArePostedWithGridsCoveringEveryLine_andTheBooksBalance() throws Exception {
        ImportJob job = runImport(alWahaWorkbook());
        assertThat(job.getStatus()).as(job.getErrors()).isEqualTo("COMPLETED");
        PortfolioImportJobDetailsDTO details = new ObjectMapper()
                .readValue(job.getErrors(), PortfolioImportJobDetailsDTO.class);
        assertThat(details.getLeasesPosted()).as(job.getErrors()).isEqualTo(3);
        assertThat(details.getWarnings() == null ? List.of() : details.getWarnings())
                .as("nothing was left as a draft").isEmpty();

        tx.executeWithoutResult(s -> {
            Property property = properties.findByTenantIdAndNameEnIn(tenantId, List.of("Al Waha Towers"))
                    .getFirst();
            // The property's own leaves, as Add Property makes them.
            Set<AccountRole> own = propertyAccounts.getMappings(property.getId()).stream()
                    .filter(m -> !m.inherited() && m.accountId() != null)
                    .map(RoleMappingDTO::role)
                    .collect(Collectors.toSet());
            assertThat(own).containsAll(EnumSet.of(AccountRole.RENT_RECEIVABLE, AccountRole.PDC_RECEIVABLE,
                    AccountRole.RENTAL_INCOME, AccountRole.SECURITY_DEPOSIT, AccountRole.ADMIN_FEE,
                    AccountRole.BANK));

            Map<String, Lease> byUnit = leases.findAll().stream()
                    .filter(l -> tenantId.equals(l.getTenantId()))
                    .collect(Collectors.toMap(l -> l.getUnit().getUnitNumber(), Function.identity()));

            for (String unit : List.of("101", "201", "301")) {
                Lease lease = byUnit.get(unit);
                assertThat(lease.getStatus()).as(unit).isEqualTo(LeaseStatus.ACTIVE);
                assertThat(lease.getPostedAt()).as(unit + " postedAt").isNotNull();
                assertThat(lease.getContractDate()).as(unit + " is posted on its agreement date")
                        .isEqualTo(lease.getAgreementDate());
                List<JournalEntry> entries = journals.findBySourceTypeAndSourceIdOrderByEntryDateAscCreatedAtAsc(
                        JournalSourceType.LEASE, lease.getId());
                assertThat(entries).extracting(JournalEntry::getDocType).as(unit).contains(JournalDocType.TCO);
                assertThat(entries).filteredOn(e -> e.getDocType() == JournalDocType.TCO)
                        .singleElement().satisfies(e -> assertThat(e.getId()).isEqualTo(lease.getPostingJournalId()));

                List<Cheque> grid = cheques.findByLease_IdOrderBySeqNoAsc(lease.getId());
                assertThat(grid).as(unit).allSatisfy(c -> {
                    assertThat(c.getStatus()).isEqualTo(ChequeStatus.REGISTERED);
                    assertThat(c.getPdrJournalId()).isNotNull();
                });
                // Residential and VAT-free, so the contract value is Σ net.
                BigDecimal contract = leaseLines.findByLease_IdOrderBySeqNoAsc(lease.getId()).stream()
                        .map(LeaseLine::getNetAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal chequeTotal = grid.stream().map(Cheque::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(chequeTotal).as(unit + " cheques = contract").isEqualByComparingTo(contract);
                assertThat(units.findById(lease.getUnit().getId()).orElseThrow().getStatus())
                        .isEqualTo(UnitStatus.OCCUPIED);
            }

            // 101: the sheet's four rent cheques keep their numbers; the deposit and
            // the admin fee are rows of their own — 38,000 + 3,800 + 1,000.
            List<Cheque> g101 = cheques.findByLease_IdOrderBySeqNoAsc(byUnit.get("101").getId());
            assertThat(g101).extracting(Cheque::getChequeNumber)
                    .startsWith("AW-CHQ-1001", "AW-CHQ-1002", "AW-CHQ-1003", "AW-CHQ-1004");
            assertThat(g101.stream().map(Cheque::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo("42800");

            // 301: MonthlyRent 8,166.67 is 98,000.00 of rent, not 98,000.04.
            Lease l301 = byUnit.get("301");
            LeaseLine rent301 = leaseLines.findByLease_IdOrderBySeqNoAsc(l301.getId()).stream()
                    .filter(l -> "RENT".equals(l.getChargeType().getCode())).findFirst().orElseThrow();
            assertThat(rent301.getNetAmount()).isEqualByComparingTo("98000.00");
            assertThat(l301.getRentAmount()).isEqualByComparingTo("98000.00");

            // 102 stays DRAFT, with the booking cheque inside a grid that still adds up.
            Lease l102 = byUnit.get("102");
            assertThat(l102.getStatus()).isEqualTo(LeaseStatus.DRAFT);
            assertThat(l102.getPostedAt()).isNull();
            List<Cheque> g102 = cheques.findByLease_IdOrderBySeqNoAsc(l102.getId());
            assertThat(g102).extracting(Cheque::getChequeNumber).contains("BD-AW-01");
            assertThat(g102.stream().map(Cheque::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo("58200");
            assertThat(units.findById(l102.getUnit().getId()).orElseThrow().getStatus())
                    .isEqualTo(UnitStatus.VACANT);

            List<TrialBalanceRowDTO> tb = ledger.trialBalance(LocalDate.of(2030, 1, 1), null);
            BigDecimal dr = tb.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal cr = tb.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(dr).isPositive();
            assertThat(dr).as("trial balance").isEqualByComparingTo(cr);
        });
    }

    /**
     * An ACTIVE row that cannot post stays DRAFT and says why — here a Cheques
     * sheet whose rent instalments are a thousand short of the rent, which the
     * validator's one-dirham tolerance does not catch but the post does.
     */
    @Test
    void anActiveRowThatCannotPost_isImportedAsDraftAndListed() throws Exception {
        Workbook wb = alWahaWorkbook();
        // 201's second cheque 36,000 → 35,999.50: inside the validator's tolerance,
        // outside the post's "Σ cheques = contract value".
        wb.getSheet("Cheques").getRow(6).getCell(8).setCellValue("35999.50");
        ImportJob job = runImport(wb);
        assertThat(job.getStatus()).as(job.getErrors()).isEqualTo("COMPLETED");
        PortfolioImportJobDetailsDTO details = new ObjectMapper()
                .readValue(job.getErrors(), PortfolioImportJobDetailsDTO.class);
        assertThat(details.getLeasesPosted()).isEqualTo(2);
        assertThat(details.getWarnings()).singleElement().satisfies(w -> {
            assertThat(w.getSheet()).isEqualTo("Leases");
            assertThat(w.getRow()).isEqualTo(3);
            assertThat(w.getMessage()).startsWith("Imported as draft: ").contains("Cheque grid totals");
        });
        tx.executeWithoutResult(s -> {
            Lease l201 = leases.findAll().stream()
                    .filter(l -> tenantId.equals(l.getTenantId()) && "201".equals(l.getUnit().getUnitNumber()))
                    .findFirst().orElseThrow();
            assertThat(l201.getStatus()).isEqualTo(LeaseStatus.DRAFT);
            assertThat(journals.findBySourceTypeAndSourceIdOrderByEntryDateAscCreatedAtAsc(
                    JournalSourceType.LEASE, l201.getId())).as("no journals at all").isEmpty();
            assertThat(units.findById(l201.getUnit().getId()).orElseThrow().getStatus())
                    .isEqualTo(UnitStatus.VACANT);
        });
    }

    /** What the pre-fix import left behind — ACTIVE, never posted — is refused by name. */
    @Test
    void anActiveLeaseThatWasNeverPosted_isRefusedClearly() throws Exception {
        runImport(alWahaWorkbook());
        UUID draftId = tx.execute(s -> leases.findAll().stream()
                .filter(l -> tenantId.equals(l.getTenantId()) && "102".equals(l.getUnit().getUnitNumber()))
                .findFirst().orElseThrow().getId());
        // Force the legacy shape: ACTIVE without a contract journal.
        tx.executeWithoutResult(s -> {
            Lease l = leases.findById(draftId).orElseThrow();
            l.setStatus(LeaseStatus.ACTIVE);
            leases.save(l);
        });
        assertThatThrownBy(() -> leasePosting.post(draftId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("ACTIVE but was never posted");
    }

    // ------------------------------------------------------------------

    private ImportJob runImport(Workbook wb) throws Exception {
        byte[] bytes;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            bytes = out.toByteArray();
        }
        ImportJob job = new ImportJob();
        job.setStatus("VALIDATING");
        job.setFileName("al-waha.xlsx");
        ImportJob saved = importJobs.save(job);
        importService.processImportAsync(bytes, saved, tenantId, admin);
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            ImportJob current = importJobs.findById(saved.getId()).orElseThrow();
            if (Set.of("COMPLETED", "VALIDATION_FAILED", "FAILED").contains(current.getStatus())) {
                return current;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("import did not finish");
    }

    /** The Al Waha Towers clean workbook from the live walk (renters' addresses are .invalid). */
    static Workbook alWahaWorkbook() {
        Workbook wb = new XSSFWorkbook();
        rows(wb.createSheet("Properties"),
                new String[]{"PropertyName", "PropertyNameAr", "Emirate", "Address", "Type", "MakaniNumber"},
                new String[]{"Al Waha Towers", "أبراج الواحة", "DUBAI", "Al Nahda 2", "RESIDENTIAL", "31025-80417"});
        rows(wb.createSheet("Units"),
                new String[]{"PropertyName", "BuildingName", "UnitNumber", "UnitType", "SizeSqft", "ExpectedRent"},
                new String[]{"Al Waha Towers", "Tower 1", "101", "STUDIO", "480", "38000"},
                new String[]{"Al Waha Towers", "Tower 1", "102", "BHK1", "760", "52000"},
                new String[]{"Al Waha Towers", "Tower 1", "201", "BHK2", "1150", "72000"},
                new String[]{"Al Waha Towers", "Tower 1", "202", "BHK2", "1150", "72000"},
                new String[]{"Al Waha Towers", "Tower 1", "301", "BHK3", "1600", "98000"},
                new String[]{"Al Waha Towers", "Tower 1", "302", "BHK1", "760", "52000"});
        rows(wb.createSheet("Renters"),
                new String[]{"Name", "NameAr", "Email", "Phone"},
                new String[]{"Khalid Rahman", "", "khalid@alwaha-sim.invalid", "+971501230001"},
                new String[]{"Priya Nair", "", "priya@alwaha-sim.invalid", "+971501230002"},
                new String[]{"Yousef Al Hammadi", "", "yousef@alwaha-sim.invalid", "+971501230003"},
                new String[]{"Elena Petrova", "", "elena@alwaha-sim.invalid", "+971501230004"});
        rows(wb.createSheet("Leases"),
                new String[]{"PropertyName", "BuildingName", "UnitNumber", "RenterEmail", "StartDate", "EndDate",
                        "RentAmount", "DepositAmount", "PaymentTerms", "PaymentMethod", "EjariNumber", "MonthlyRent",
                        "AdminFee", "ParkingRemoteFee", "RentVatApplicable", "AdminFeeVatApplicable",
                        "SecurityDepositVatApplicable", "ParkingRemoteVatApplicable", "DepositPaymentMethod",
                        "AgreementDate", "Status", "BookingDeposit_Amount", "BookingDeposit_Number",
                        "BookingDeposit_Date", "BookingDeposit_Bank"},
                new String[]{"Al Waha Towers", "Tower 1", "101", "khalid@alwaha-sim.invalid", "2026-01-01",
                        "2026-12-31", "38000", "3800", "4", "CHEQUE", "EJ-2026-AW101", "", "1000", "0", "", "", "",
                        "", "CHEQUE", "2025-12-20", "ACTIVE", "", "", "", ""},
                new String[]{"Al Waha Towers", "Tower 1", "201", "priya@alwaha-sim.invalid", "2026-06-01",
                        "2027-05-31", "72000", "7200", "2", "CHEQUE", "EJ-2026-AW201", "", "1000", "0", "", "", "",
                        "", "CHEQUE", "2026-05-20", "ACTIVE", "", "", "", ""},
                new String[]{"Al Waha Towers", "Tower 1", "301", "yousef@alwaha-sim.invalid", "2026-09-01",
                        "2027-08-31", "", "9800", "12", "CHEQUE", "EJ-2026-AW301", "8166.67", "1000", "0", "", "",
                        "", "", "CHEQUE", "2026-08-25", "ACTIVE", "", "", "", ""},
                new String[]{"Al Waha Towers", "Tower 1", "102", "elena@alwaha-sim.invalid", "2026-11-01",
                        "2027-10-31", "52000", "5200", "4", "CHEQUE", "", "", "1000", "0", "", "", "", "",
                        "BANK_TRANSFER", "2026-09-24", "DRAFT", "5000", "BD-AW-01", "2026-09-24", "Emirates NBD"});
        rows(wb.createSheet("Cheques"),
                new String[]{"PropertyName", "UnitNumber", "RenterEmail", "InstallmentNo", "DueDate",
                        "ChequeOrPaymentDate", "UniqueId", "Bank", "Amount", "Method"},
                chq("101", "khalid", "1", "2026-01-01", "AW-CHQ-1001", "Mashreq", "9500"),
                chq("101", "khalid", "2", "2026-04-01", "AW-CHQ-1002", "Mashreq", "9500"),
                chq("101", "khalid", "3", "2026-07-01", "AW-CHQ-1003", "Mashreq", "9500"),
                chq("101", "khalid", "4", "2026-10-01", "AW-CHQ-1004", "Mashreq", "9500"),
                chq("201", "priya", "1", "2026-06-01", "AW-CHQ-2001", "FAB", "36000"),
                chq("201", "priya", "2", "2026-12-01", "AW-CHQ-2002", "FAB", "36000"));
        return wb;
    }

    private static String[] chq(String unit, String who, String no, String date, String number, String bank,
                                String amount) {
        return new String[]{"Al Waha Towers", unit, who + "@alwaha-sim.invalid", no, date, date, number, bank,
                amount, "CHEQUE"};
    }

    private static void rows(Sheet sheet, String[]... values) {
        for (int r = 0; r < values.length; r++) {
            Row row = sheet.createRow(r);
            for (int c = 0; c < values[r].length; c++) {
                row.createCell(c).setCellValue(values[r][c]);
            }
        }
    }
}
