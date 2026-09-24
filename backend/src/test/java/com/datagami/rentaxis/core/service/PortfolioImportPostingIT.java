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
import java.time.ZoneId;
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
 * puts its ACTIVE rows <em>on the books</em> — and, after the PR #344 review, only
 * the ones it can post truthfully (C1), with exact sheet totals (I3), VAT on rent
 * (I1), the rent taken from the cheques (I2) and only generated rows unnumbered (I5).
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

    /** Today on the app clock — the day the running-tenancy rule (review C1) measures against. */
    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Dubai"));
    }

    /** The first day of next month: tenancies that start after today, so no cheque is in the past. */
    private static LocalDate nextMonth() {
        return today().plusMonths(1).withDayOfMonth(1);
    }

    @Test
    void activeRowsArePostedWithGridsCoveringEveryLine_andTheBooksBalance() throws Exception {
        ImportJob job = runImport(alWahaWorkbook(nextMonth()));
        assertThat(job.getStatus()).as(job.getErrors()).isEqualTo("COMPLETED");
        PortfolioImportJobDetailsDTO details = new ObjectMapper()
                .readValue(job.getErrors(), PortfolioImportJobDetailsDTO.class);
        assertThat(details.getLeasesPosted()).as(job.getErrors()).isEqualTo(3);
        // Nothing was left as a draft. The one warning is the count of generated
        // rows that went on the books without a number (review I5): 101 and 201
        // each have a deposit row and an admin-fee row; 301 has no Cheques sheet,
        // so all twelve of its rows are generated. The sheet rows are not counted.
        assertThat(details.getChequesWithoutNumber()).isEqualTo(16);
        assertThat(details.getWarnings()).singleElement().satisfies(w -> {
            assertThat(w.getSheet()).isEqualTo("Cheques");
            assertThat(w.getMessage()).isEqualTo("16 cheques imported without a number — add them in Cheque details");
        });

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

            Map<String, Lease> byUnit = leasesByUnit();

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
            // the admin fee are rows of their own, dated the contract date (today —
            // not before it, so the running-tenancy rule lets them through) and
            // unnumbered — 38,000 + 3,800 + 1,000.
            List<Cheque> g101 = cheques.findByLease_IdOrderBySeqNoAsc(byUnit.get("101").getId());
            assertThat(g101).extracting(Cheque::getChequeNumber)
                    .containsExactly("AW-CHQ-1001", "AW-CHQ-1002", "AW-CHQ-1003", "AW-CHQ-1004", null, null);
            assertThat(g101.get(4).getChequeDate()).isEqualTo(today());
            assertThat(g101.stream().map(Cheque::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo("42800");

            // 301: MonthlyRent 8,166.67 with no Cheques sheet is 98,000.00 of rent, not 98,000.04.
            Lease l301 = byUnit.get("301");
            assertThat(rentLine(l301).getNetAmount()).isEqualByComparingTo("98000.00");
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

            assertTrialBalanceBalances();
        });
    }

    /**
     * Review C1: a tenancy already running when it is imported has cheques dated
     * before today, and the v1 sheet cannot say which of them were banked. Posting
     * would register them all as outstanding PDCs — overdue e-mails and "pay now"
     * for rent already paid — so the row stays DRAFT, says why, and leaves nothing
     * on the books. The rows whose cheques are all today or later still post.
     */
    @Test
    void aRunningTenancyWithPastDatedCheques_isImportedAsDraftAndPointedAtTheCutOver() throws Exception {
        Workbook wb = alWahaWorkbook(nextMonth());
        // 101 started nine months ago: its first cheques are in the past.
        LocalDate started = nextMonth().minusMonths(9);
        retime101(wb, started);
        ImportJob job = runImport(wb);
        assertThat(job.getStatus()).as(job.getErrors()).isEqualTo("COMPLETED");
        PortfolioImportJobDetailsDTO details = new ObjectMapper()
                .readValue(job.getErrors(), PortfolioImportJobDetailsDTO.class);
        assertThat(details.getLeasesPosted()).isEqualTo(2);
        assertThat(details.getWarnings()).filteredOn(w -> "Leases".equals(w.getSheet()))
                .singleElement().satisfies(w -> {
                    assertThat(w.getRow()).isEqualTo(2);
                    assertThat(w.getMessage()).isEqualTo("Imported as draft: Running tenancy with past-dated"
                            + " cheques — import it through the cut-over import (Contracts sheet), which"
                            + " records each cheque's status");
                });
        // 101's generated rows did not go on the books, so they are not counted.
        assertThat(details.getChequesWithoutNumber()).isEqualTo(14);
        tx.executeWithoutResult(s -> {
            Map<String, Lease> byUnit = leasesByUnit();
            Lease l101 = byUnit.get("101");
            assertThat(l101.getStatus()).isEqualTo(LeaseStatus.DRAFT);
            assertThat(l101.getPostedAt()).isNull();
            assertThat(journals.findBySourceTypeAndSourceIdOrderByEntryDateAscCreatedAtAsc(
                    JournalSourceType.LEASE, l101.getId())).as("no journals at all").isEmpty();
            assertThat(cheques.findByLease_IdOrderBySeqNoAsc(l101.getId()))
                    .as("no cheque registered, so none can be overdue")
                    .allSatisfy(c -> assertThat(c.getStatus()).isEqualTo(ChequeStatus.DRAFT));
            assertThat(units.findById(l101.getUnit().getId()).orElseThrow().getStatus())
                    .isEqualTo(UnitStatus.VACANT);
            assertThat(byUnit.get("201").getStatus()).isEqualTo(LeaseStatus.ACTIVE);
            assertThat(byUnit.get("301").getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        });
    }

    /**
     * Review I3: the validator is exact, like the post. A Cheques sheet fifty fils
     * short used to validate clean (AED 1 tolerance) and then import as a draft.
     */
    @Test
    void aChequesSheetThatIsOffByAFils_failsValidation() throws Exception {
        Workbook wb = alWahaWorkbook(nextMonth());
        // 201's second cheque 36,000 → 35,999.99.
        wb.getSheet("Cheques").getRow(6).getCell(8).setCellValue("35999.99");
        ImportJob job = runImport(wb);
        assertThat(job.getStatus()).isEqualTo("VALIDATION_FAILED");
        assertThat(job.getErrors()).contains("Sum of cheques (71999.99) does not match lease total rent (72000)");
    }

    /**
     * Review I2: with a Cheques sheet the rent is what the rent cheques add up to,
     * so twelve cheques of 8,166.67 make a rent of 98,000.04 — the grid matches the
     * contract to the fils and the lease posts. (Without a sheet the same
     * MonthlyRent is 98,000.00, above.)
     */
    @Test
    void monthlyRentWithAChequesSheet_takesTheRentFromTheCheques() throws Exception {
        Workbook wb = alWahaWorkbook(nextMonth());
        Sheet sheet = wb.getSheet("Cheques");
        for (int m = 0; m < 12; m++) {
            String date = nextMonth().plusMonths(m).toString();
            addRow(sheet, "Al Waha Towers", "301", "yousef@alwaha-sim.invalid", String.valueOf(m + 1), date, date,
                    "AW-CHQ-30" + String.format("%02d", m + 1), "ADCB", "8166.67", "CHEQUE");
        }
        ImportJob job = runImport(wb);
        assertThat(job.getStatus()).as(job.getErrors()).isEqualTo("COMPLETED");
        PortfolioImportJobDetailsDTO details = new ObjectMapper()
                .readValue(job.getErrors(), PortfolioImportJobDetailsDTO.class);
        assertThat(details.getLeasesPosted()).as(job.getErrors()).isEqualTo(3);
        tx.executeWithoutResult(s -> {
            Lease l301 = leasesByUnit().get("301");
            assertThat(l301.getStatus()).isEqualTo(LeaseStatus.ACTIVE);
            assertThat(rentLine(l301).getNetAmount()).isEqualByComparingTo("98000.04");
            List<Cheque> grid = cheques.findByLease_IdOrderBySeqNoAsc(l301.getId());
            // 12 sheet cheques + the deposit and admin-fee rows.
            assertThat(grid).hasSize(14);
            assertThat(grid.stream().map(Cheque::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo("108800.04");
            assertTrialBalanceBalances();
        });
    }

    /**
     * Review I1: a commercial lease carries 5% VAT on rent, and its Cheques sheet
     * states what the renter writes — the rent with the VAT. It used to be checked
     * against the net rent and could never post.
     */
    @Test
    void aCommercialLeaseWithVatOnRentAndAChequesSheet_posts() throws Exception {
        Workbook wb = alWahaWorkbook(nextMonth());
        addCommercialLease(wb, nextMonth());
        ImportJob job = runImport(wb);
        assertThat(job.getStatus()).as(job.getErrors()).isEqualTo("COMPLETED");
        PortfolioImportJobDetailsDTO details = new ObjectMapper()
                .readValue(job.getErrors(), PortfolioImportJobDetailsDTO.class);
        assertThat(details.getLeasesPosted()).as(job.getErrors()).isEqualTo(4);
        tx.executeWithoutResult(s -> {
            Lease office = leasesByUnit().get("G01");
            assertThat(office.getStatus()).isEqualTo(LeaseStatus.ACTIVE);
            assertThat(office.isRentVatApplicable()).as("COMMERCIAL defaults to VAT on rent").isTrue();
            LeaseLine rent = rentLine(office);
            assertThat(rent.getNetAmount()).isEqualByComparingTo("100000.00");
            List<Cheque> grid = cheques.findByLease_IdOrderBySeqNoAsc(office.getId());
            // 2 × 52,500 of rent incl. VAT, and the 10,000 deposit (never taxed).
            assertThat(grid.stream().map(Cheque::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo("115000.00");
            assertThat(grid).extracting(Cheque::getChequeNumber).startsWith("AWO-1", "AWO-2");
            assertTrialBalanceBalances();
        });
    }

    /** What the pre-fix import left behind — ACTIVE, never posted — is refused by name. */
    @Test
    void anActiveLeaseThatWasNeverPosted_isRefusedClearly() throws Exception {
        runImport(alWahaWorkbook(nextMonth()));
        UUID draftId = tx.execute(s -> leasesByUnit().get("102").getId());
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

    private Map<String, Lease> leasesByUnit() {
        return leases.findAll().stream()
                .filter(l -> tenantId.equals(l.getTenantId()))
                .collect(Collectors.toMap(l -> l.getUnit().getUnitNumber(), Function.identity()));
    }

    private LeaseLine rentLine(Lease lease) {
        return leaseLines.findByLease_IdOrderBySeqNoAsc(lease.getId()).stream()
                .filter(l -> "RENT".equals(l.getChargeType().getCode())).findFirst().orElseThrow();
    }

    private void assertTrialBalanceBalances() {
        List<TrialBalanceRowDTO> tb = ledger.trialBalance(LocalDate.of(2099, 1, 1), null);
        BigDecimal dr = tb.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cr = tb.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(dr).isPositive();
        assertThat(dr).as("trial balance").isEqualByComparingTo(cr);
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

    /**
     * The Al Waha Towers clean workbook from the live walk (renters' addresses are
     * .invalid), re-dated so every tenancy starts on {@code start} and every
     * agreement is signed today: nothing is dated before today, so every ACTIVE
     * row is one the import may post (review C1).
     */
    static Workbook alWahaWorkbook(LocalDate start) {
        String today = today().toString();
        String s0 = start.toString();
        String end = start.plusYears(1).minusDays(1).toString();
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
                LEASE_HEADERS,
                new String[]{"Al Waha Towers", "Tower 1", "101", "khalid@alwaha-sim.invalid", s0,
                        end, "38000", "3800", "4", "CHEQUE", "EJ-2026-AW101", "", "1000", "0", "", "", "",
                        "", "CHEQUE", today, "ACTIVE", "", "", "", ""},
                new String[]{"Al Waha Towers", "Tower 1", "201", "priya@alwaha-sim.invalid", s0,
                        end, "72000", "7200", "2", "CHEQUE", "EJ-2026-AW201", "", "1000", "0", "", "", "",
                        "", "CHEQUE", today, "ACTIVE", "", "", "", ""},
                new String[]{"Al Waha Towers", "Tower 1", "301", "yousef@alwaha-sim.invalid", s0,
                        end, "", "9800", "12", "CHEQUE", "EJ-2026-AW301", "8166.67", "1000", "0", "", "",
                        "", "", "CHEQUE", today, "ACTIVE", "", "", "", ""},
                new String[]{"Al Waha Towers", "Tower 1", "102", "elena@alwaha-sim.invalid", s0,
                        end, "52000", "5200", "4", "CHEQUE", "", "", "1000", "0", "", "", "", "",
                        "BANK_TRANSFER", today, "DRAFT", "5000", "BD-AW-01", today, "Emirates NBD"});
        rows(wb.createSheet("Cheques"),
                new String[]{"PropertyName", "UnitNumber", "RenterEmail", "InstallmentNo", "DueDate",
                        "ChequeOrPaymentDate", "UniqueId", "Bank", "Amount", "Method"},
                chq("101", "khalid", "1", start, "AW-CHQ-1001", "Mashreq", "9500"),
                chq("101", "khalid", "2", start.plusMonths(3), "AW-CHQ-1002", "Mashreq", "9500"),
                chq("101", "khalid", "3", start.plusMonths(6), "AW-CHQ-1003", "Mashreq", "9500"),
                chq("101", "khalid", "4", start.plusMonths(9), "AW-CHQ-1004", "Mashreq", "9500"),
                chq("201", "priya", "1", start, "AW-CHQ-2001", "FAB", "36000"),
                chq("201", "priya", "2", start.plusMonths(6), "AW-CHQ-2002", "FAB", "36000"));
        return wb;
    }

    private static final String[] LEASE_HEADERS = {"PropertyName", "BuildingName", "UnitNumber", "RenterEmail",
            "StartDate", "EndDate", "RentAmount", "DepositAmount", "PaymentTerms", "PaymentMethod", "EjariNumber",
            "MonthlyRent", "AdminFee", "ParkingRemoteFee", "RentVatApplicable", "AdminFeeVatApplicable",
            "SecurityDepositVatApplicable", "ParkingRemoteVatApplicable", "DepositPaymentMethod",
            "AgreementDate", "Status", "BookingDeposit_Amount", "BookingDeposit_Number",
            "BookingDeposit_Date", "BookingDeposit_Bank"};

    /** 101 as a tenancy already running: started on {@code started}, signed ten days before it. */
    private static void retime101(Workbook wb, LocalDate started) {
        Row lease = wb.getSheet("Leases").getRow(1);
        lease.getCell(4).setCellValue(started.toString());
        lease.getCell(5).setCellValue(started.plusYears(1).minusDays(1).toString());
        lease.getCell(19).setCellValue(started.minusDays(10).toString());
        Sheet cheques = wb.getSheet("Cheques");
        for (int r = 1; r <= 4; r++) {
            String date = started.plusMonths(3L * (r - 1)).toString();
            cheques.getRow(r).getCell(4).setCellValue(date);
            cheques.getRow(r).getCell(5).setCellValue(date);
        }
    }

    /**
     * A second, COMMERCIAL property with one office let for 100,000 a year plus 5%
     * VAT: two sheet cheques of 52,500 and a 10,000 deposit.
     */
    private static void addCommercialLease(Workbook wb, LocalDate start) {
        addRow(wb.getSheet("Properties"), "Al Waha Offices", "", "DUBAI", "Al Nahda 2", "COMMERCIAL", "");
        addRow(wb.getSheet("Units"), "Al Waha Offices", "", "G01", "OFFICE", "2000", "100000");
        addRow(wb.getSheet("Renters"), "Sana Trading LLC", "", "sana@alwaha-sim.invalid", "+971501230005");
        addRow(wb.getSheet("Leases"), "Al Waha Offices", "", "G01", "sana@alwaha-sim.invalid", start.toString(),
                start.plusYears(1).minusDays(1).toString(), "100000", "10000", "2", "CHEQUE", "", "", "", "",
                "", "", "", "", "CHEQUE", today().toString(), "ACTIVE", "", "", "", "");
        addRow(wb.getSheet("Cheques"), "Al Waha Offices", "G01", "sana@alwaha-sim.invalid", "1",
                start.toString(), start.toString(), "AWO-1", "FAB", "52500", "CHEQUE");
        addRow(wb.getSheet("Cheques"), "Al Waha Offices", "G01", "sana@alwaha-sim.invalid", "2",
                start.plusMonths(6).toString(), start.plusMonths(6).toString(), "AWO-2", "FAB", "52500", "CHEQUE");
    }

    private static void addRow(Sheet sheet, String... values) {
        Row row = sheet.createRow(sheet.getLastRowNum() + 1);
        for (int c = 0; c < values.length; c++) {
            row.createCell(c).setCellValue(values[c]);
        }
    }

    private static String[] chq(String unit, String who, String no, LocalDate date, String number, String bank,
                                String amount) {
        return new String[]{"Al Waha Towers", unit, who + "@alwaha-sim.invalid", no, date.toString(),
                date.toString(), number, bank, amount, "CHEQUE"};
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
