package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.service.vat.VatReturnService;
import com.datagami.rentaxis.core.service.cutover.ContractImportPostService.BulkPostResult;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S16-14: a building bought after go-live brings its running tenancies in — the
 * acquisition cut-over.
 *
 * <p>The organisation went live on 01/10/2026 (books locked through 30/09): the
 * template's Sample Tower was cut over and the opening balances posted. On 15/10/2026
 * it bought "Acquired Tower", let on the same two contracts the template carries:</p>
 * <ul>
 *   <li>ACQ-0001: 51,000 rent over 24/09/2026 – 23/09/2027 (365 days) and a 5,000
 *       deposit; cheque 1 (31,000) banked by the previous owner on 25/09, cheque 2
 *       (25,000, March 2027) handed over. Earned before the acquisition: 21 days,
 *       2,934.25.</li>
 *   <li>ACQ-0002: 21,000 rent + 5% VAT over 01/10/2026 – 30/09/2027 on the CONTRACT
 *       model; its 22,050 cheque dated 01/10 handed over unbanked. Earned before the
 *       acquisition: 14 days, 805.48.</li>
 * </ul>
 * <p>At 15/10 the acquired building's books hold the cheques handed over (47,050.00
 * PDC), the unearned rent (68,260.27), the deposit (5,000.00) and the opening position
 * with the previous owner — its vendor account, "Due to/from vendor - Acquired Tower"
 * (ACQUISITION_CLEARING, #376 P2-2) — (26,210.27 Dr = 31,000 banked by them
 * − 2,934.25 − 805.48 earned by them − 1,050.00 VAT they declared). No income, no VAT,
 * nothing dated before 15/10. Today (the fixed clock) is 15/11/2026.</p>
 */
@SpringBootTest
@Import(AcquisitionCutoverIT.FixedClockConfig.class)
class AcquisitionCutoverIT extends AbstractPostgresIT {

    static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");
    static final LocalDate TODAY = LocalDate.of(2026, 11, 15);
    static final LocalDate A = LocalDate.of(2026, 10, 15);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(TODAY.atTime(10, 0).atZone(DUBAI).toInstant(), DUBAI);
        }
    }

    @Autowired CutoverFixture fixture;
    @Autowired ContractImportPersistService contractPersist;
    @Autowired ContractImportPostService postService;
    @Autowired ImportBatchService batches;
    @Autowired ImportBatchDiscardService discards;
    @Autowired OpeningBalanceService openingBalances;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired RecognitionService recognition;
    @Autowired VatReturnService vatReturns;
    @Autowired PostingService posting;
    @Autowired AccountResolver resolver;
    @Autowired LeaseRepository leaseRepo;
    @Autowired ChequeRepository chequeRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.datagami.rentaxis.core.service.lease.LeaseTerminationService termination;
    @Autowired com.datagami.rentaxis.core.service.lease.LeaseReductionService reductions;
    @Autowired com.datagami.rentaxis.core.service.lease.LeasePostingService leasePosting;
    @Autowired com.datagami.rentaxis.core.service.LeaseService leaseService;
    @Autowired com.datagami.rentaxis.core.service.cheque.ChequeService chequeService;
    @Autowired com.datagami.rentaxis.core.service.RentReceiptService receipts;

    UUID tenantId;

    @BeforeEach
    void setUp() throws Exception {
        tenantId = fixture.newCutOverTenant("ACQ");
        fixture.authenticateAsTenantAdmin();
        // Go-live: the template's Sample Tower cut over, then the opening balances.
        UUID goLive;
        try (Workbook wb = fixture.template()) {
            goLive = contractPersist.persist(wb, fixture.newJob()).batchId();
        }
        assertThat(postService.post(goLive).leasesFailed()).isZero();
        UUID capital = tx.execute(s -> resolver.resolve(AccountRole.OPENING_BALANCE_DIFFERENCE, null)).getId();
        UUID bank = tx.execute(s -> jdbc.queryForObject(
                "select id from accounts where tenant_id = ? and name = 'Sample Bank - ST1'", UUID.class, tenantId));
        openingBalances.setRow(bank, new BigDecimal("1000.00"), BigDecimal.ZERO);
        openingBalances.post();
        assertThat(fiscal.hasLiveOpeningBalance()).isTrue();
        assertThat(capital).isNotNull();
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
        fixture.clearAuthentication();
    }

    // ------------------------------------------------------------------ the acquisition

    @Test
    void anAcquiredBuildingComesInWithItsOwnOpeningPositionDatedTheAcquisition() throws Exception {
        String obBefore = obJournal();
        long renters = renterCount();
        UUID batchId = importAcquisition(null);

        batches.setAcquisitionDate(batchId, A);
        BulkPostResult result = postService.post(batchId);

        assertThat(result.failures()).isEmpty();
        assertThat(result.leasesPosted()).isEqualTo(2);
        // The renters the organisation already has are re-used, not duplicated.
        assertThat(renterCount()).isEqualTo(renters);
        // The go-live opening balances are untouched.
        assertThat(obJournal()).isEqualTo(obBefore);

        // Nothing of the acquisition is dated before it.
        assertThat(jdbc.queryForObject("select min(entry_date) from journal_entries where import_batch_id = ?",
                LocalDate.class, batchId)).isEqualTo(A);
        UUID property = acquiredProperty();
        LocalDate booksStart = tx.execute(s -> propertyRepo.findById(property).orElseThrow().getBooksStartDate());
        assertThat(booksStart).isEqualTo(A);

        // The opening position at 15/10.
        assertThat(balance(property, AccountRole.PDC_RECEIVABLE, A)).isEqualByComparingTo("47050.00");
        assertThat(balance(property, AccountRole.ADVANCE_RENT, A)).isEqualByComparingTo("-68260.27");
        assertThat(balance(property, AccountRole.SECURITY_DEPOSIT, A)).isEqualByComparingTo("-5000.00");
        assertThat(balance(property, AccountRole.RENTAL_INCOME, A)).isEqualByComparingTo("0");
        assertThat(balance(property, AccountRole.OUTPUT_VAT, A)).isEqualByComparingTo("0");
        assertThat(balance(property, AccountRole.RENT_RECEIVABLE, A)).isEqualByComparingTo("0");
        assertThat(balance(property, AccountRole.ACQUISITION_CLEARING, A)).isEqualByComparingTo("26210.27");

        // The cheque the previous owner banked is cleared on its own dates with no bank entry;
        // the ones handed over are ours to bank.
        List<Cheque> first = cheques("SAMPLE-0001");
        assertThat(first.get(0).getStatus()).isEqualTo(ChequeStatus.CLEARED);
        assertThat(first.get(0).getClearedAt()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(first.get(0).getCrtJournalId()).isNull();
        assertThat(first.get(1).getStatus()).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(cheques("SAMPLE-0002").get(0).getStatus()).isEqualTo(ChequeStatus.REGISTERED);

        // Recognition: the previous owner's days are one row released to the opening
        // position; the rest is planned from 15/10 and adds up to the contract.
        for (String ref : List.of("SAMPLE-0001", "SAMPLE-0002")) {
            UUID leaseId = leaseIdOf(ref);
            var rows = tx.execute(s -> recognition.scheduleFor(leaseId)).stream()
                    .filter(r -> r.status() != RecognitionStatus.CANCELLED).toList();
            assertThat(rows.stream().map(r -> r.amount()).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo("SAMPLE-0001".equals(ref) ? "51000.00" : "21000.00");
            assertThat(rows).filteredOn(r -> r.status() == RecognitionStatus.PLANNED)
                    .allSatisfy(r -> assertThat(r.periodStart()).isAfterOrEqualTo(A));
        }

        // The rest of the term is ours: at its end nothing is deferred and the income is
        // exactly what was unearned at 15/10.
        recognition.runTo(LocalDate.of(2027, 9, 30), false);
        LocalDate end = LocalDate.of(2027, 9, 30);
        assertThat(balance(property, AccountRole.ADVANCE_RENT, end)).isEqualByComparingTo("0");
        assertThat(balance(property, AccountRole.RENTAL_INCOME, end)).isEqualByComparingTo("-68260.27");

        // The quarter of the acquisition ties: the previous owner's VAT nets out on the day.
        var q4 = tx.execute(s -> vatReturns.get(LocalDate.of(2026, 10, 1))).outputCheck();
        assertThat(q4.ok()).as("Q4 output check " + q4).isTrue();

        // Nothing may be dated before the acquired building's books start, whatever posts it.
        assertThatThrownBy(() -> posting.post(PostingRequest.ofPairs(JournalDocType.JV, A.minusDays(1), "Before",
                PostingRequest.Dimensions.ofProperty(property), JournalSourceType.MANUAL, null, null,
                List.of(PostingRequest.pair(PostingRequest.dr(AccountRole.BANK, new BigDecimal("10")),
                        PostingRequest.cr(AccountRole.OTHER_INCOME, new BigDecimal("10")))))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("start on " + A);
    }

    /** An acquisition batch reverses with the opening balances live — until its date is locked. */
    @Test
    void anAcquisitionReversesLikeACutOverUntilItsDateIsLocked() throws Exception {
        UUID batchId = importAcquisition(null);
        batches.setAcquisitionDate(batchId, A);
        assertThat(postService.post(batchId).leasesPosted()).isEqualTo(2);

        tx.executeWithoutResult(s -> fiscal.lockThrough(LocalDate.of(2026, 10, 31)));
        assertThatThrownBy(() -> batches.reverse(batchId, "wrong"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("locked through 2026-10-31");

        tx.executeWithoutResult(s -> jdbc.update(
                "update tenant_fiscal_settings set books_locked_through = ? where tenant_id = ?",
                LocalDate.of(2026, 9, 30), tenantId));
        assertThat(batches.reverse(batchId, "wrong").getStatus()).isEqualTo(ImportBatchStatus.REVERSED);
        assertThat(balance(acquiredProperty(), AccountRole.ACQUISITION_CLEARING, TODAY)).isEqualByComparingTo("0");
        // #376 P3-2: the acquisition's books start goes with it.
        assertThat(jdbc.queryForObject("select books_start_date from properties where id = ?", LocalDate.class,
                acquiredProperty())).isNull();
    }

    // ------------------------------------------------------------------ after the acquisition (#376 review)

    /** The acquisition, posted: 26,210.27 due from the vendor, nothing on our Output VAT. */
    private UUID acquire() throws Exception {
        UUID batchId = importAcquisition(null);
        batches.setAcquisitionDate(batchId, A);
        assertThat(postService.post(batchId).failures()).isEmpty();
        return batchId;
    }

    /**
     * P1-1 (the reviewer's probe): terminating an acquired VAT lease on 10/11 hands back
     * 932.06 of VAT the previous owner declared. The vendor refunds it — no credit note
     * of ours, our Output VAT and the Q4 return untouched.
     */
    @Test
    void terminatingAnAcquiredVatLeaseHandsTheVendorsVatBackThroughTheVendor() throws Exception {
        acquire();
        UUID property = acquiredProperty();
        UUID leaseId = leaseIdOf("SAMPLE-0002");
        termination.terminate(leaseId, new com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest(
                LocalDate.of(2026, 11, 10), null, null, null), null);

        assertThat(jdbc.queryForObject("select count(*) from tax_invoices where lease_id = ?", Integer.class, leaseId))
                .as("no tax document of ours").isZero();
        assertThat(balance(property, AccountRole.OUTPUT_VAT, TODAY)).isEqualByComparingTo("0");
        assertThat(balance(property, AccountRole.ACQUISITION_CLEARING, TODAY)).isEqualByComparingTo("27142.33");
        var q4 = tx.execute(s -> vatReturns.get(LocalDate.of(2026, 10, 1)));
        assertThat(q4.outputCheck().ok()).isTrue();
        assertThat(q4.netVat()).as("no refund claimed on our return").isEqualByComparingTo("0");
    }

    /**
     * P1-1 and P3-1: an amendment cutting ACQ-0002's rent from 21,000 to 14,600 on 15/11
     * (a one-off fee keeps the cheque whole). The 320.00 of VAT it takes off is the
     * vendor's; the previous owner's 14 days are re-priced (805.48 → 560.00) against the
     * vendor, never through our income. At the end of the term Advance Rent is nil and our
     * rental income is exactly our days of both contracts.
     */
    @Test
    void amendingAnAcquiredLeaseKeepsTheVendorsVatAndDaysWithTheVendor() throws Exception {
        acquire();
        UUID property = acquiredProperty();
        UUID leaseId = leaseIdOf("SAMPLE-0002");
        UUID adminFee = jdbc.queryForObject("select id from charge_types where tenant_id = ? and code = 'ADMIN_FEE'",
                UUID.class, tenantId);
        List<com.datagami.rentaxis.api.dto.lease.LeaseLineInput> lines = new java.util.ArrayList<>();
        for (var l : tx.execute(s -> leaseService.getLines(leaseId))) {
            lines.add(new com.datagami.rentaxis.api.dto.lease.LeaseLineInput(l.chargeTypeId(), null,
                    new BigDecimal("14600"), l.discountAmount(), l.narration(), l.vatApplicable(), l.creditAccountId(),
                    l.periodStart(), l.periodEnd(), l.addendumId()));
        }
        lines.add(new com.datagami.rentaxis.api.dto.lease.LeaseLineInput(adminFee, null, new BigDecimal("6720"),
                BigDecimal.ZERO, "Admin fee", false, null, null, null, null));
        leasePosting.amendLines(leaseId, lines, "Rent corrected");

        assertThat(jdbc.queryForObject("select count(*) from tax_invoices where lease_id = ?", Integer.class, leaseId))
                .as("no tax document of ours").isZero();
        assertThat(balance(property, AccountRole.OUTPUT_VAT, TODAY)).isEqualByComparingTo("0");
        assertThat(balance(property, AccountRole.ACQUISITION_CLEARING, TODAY)).isEqualByComparingTo("26775.75");

        LocalDate end = LocalDate.of(2027, 9, 30);
        recognition.runTo(end, false);
        assertThat(balance(property, AccountRole.ADVANCE_RENT, end)).isEqualByComparingTo("0");
        assertThat(balance(property, AccountRole.RENTAL_INCOME, end)).isEqualByComparingTo("-62105.75");
    }

    /** P1-1: a credit addendum on an acquired VAT lease — the VAT it takes off is the vendor's too. */
    @Test
    void aCreditAddendumOnAnAcquiredLeaseIssuesNoCreditNoteOfOurs() throws Exception {
        acquire();
        UUID property = acquiredProperty();
        UUID leaseId = leaseIdOf("SAMPLE-0002");
        UUID rentLine = tx.execute(s -> leaseService.getLines(leaseId)).get(0).id();
        LocalDate e = LocalDate.of(2026, 11, 10);
        reductions.reduce(leaseId, new com.datagami.rentaxis.api.dto.lease.ReduceLeaseRequest(e, e, "Rent renegotiated",
                null, List.of(new com.datagami.rentaxis.api.dto.lease.ReduceLeaseRequest.LineReduction(rentLine,
                        new BigDecimal("14600"))), "CREDIT", List.of(), List.of()));
        assertThat(jdbc.queryForObject("select count(*) from tax_invoices where lease_id = ?", Integer.class, leaseId))
                .as("no tax document of ours").isZero();
        assertThat(balance(property, AccountRole.OUTPUT_VAT, TODAY)).isEqualByComparingTo("0");
        assertThat(balance(property, AccountRole.ACQUISITION_CLEARING, TODAY)).isGreaterThan(new BigDecimal("26210.27"));
    }

    /** P2-1: the cheque the previous owner banked is not ours to bounce, receipt or count as collected. */
    @Test
    void aChequeTheVendorBankedIsNotOurs() throws Exception {
        acquire();
        Cheque banked = cheques("SAMPLE-0001").get(0);
        assertThat(banked.isSettledBeforeAcquisition()).isTrue();
        assertThatThrownBy(() -> chequeService.bounce(banked.getId(),
                new com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest(LocalDate.of(2026, 11, 1), null, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("banked by the previous owner before " + A)
                .hasMessageContaining("acquisition clearing account");
        assertThatThrownBy(() -> receipts.receipt(banked.getId()))
                .hasMessageContaining("banked by the previous owner");
        // The dashboard's collected tile counts go-live's 31,000 cleared cheque, not the vendor's.
        BigDecimal cleared = tx.execute(s -> chequeRepo.totalsByStatus(null, true, List.of()).stream()
                .filter(r -> r[0] == ChequeStatus.CLEARED).map(r -> (BigDecimal) r[2]).findFirst().orElse(BigDecimal.ZERO));
        assertThat(cleared).isEqualByComparingTo("31000.00");
    }

    /** P3-2: a property whose books already start on another day is not moved by a second acquisition. */
    @Test
    void aPropertyWhoseBooksStartElsewhereIsRefused() throws Exception {
        UUID batchId = importAcquisition(null);
        jdbc.update("update properties set books_start_date = ? where id = ?", LocalDate.of(2026, 10, 10), acquiredProperty());
        batches.setAcquisitionDate(batchId, A);
        assertThatThrownBy(() -> postService.post(batchId))
                .hasMessageContaining("already start on 2026-10-10");
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    void theDateMustBeInTheOpenBooks() throws Exception {
        UUID batchId = importAcquisition(null);
        batches.setAcquisitionDate(batchId, LocalDate.of(2026, 9, 20));
        assertThatThrownBy(() -> postService.post(batchId)).hasMessageContaining("before the books start");

        batches.setAcquisitionDate(batchId, LocalDate.of(2026, 11, 20));
        assertThatThrownBy(() -> postService.post(batchId)).hasMessageContaining("in the future");

        tx.executeWithoutResult(s -> fiscal.lockThrough(LocalDate.of(2026, 10, 20)));
        batches.setAcquisitionDate(batchId, A);
        assertThatThrownBy(() -> postService.post(batchId)).hasMessageContaining("books are locked through");
        UUID acquired = acquiredProperty();
        LocalDate booksStart = tx.execute(s -> propertyRepo.findById(acquired).orElseThrow().getBooksStartDate());
        assertThat(booksStart).isNull();
    }

    /** Only a building new to the books: one with postings of its own is refused whole. */
    @Test
    void aBuildingWithPostingsIsNotAnAcquisition() throws Exception {
        UUID batchId = importAcquisition(null);
        UUID property = acquiredProperty();
        posting.post(PostingRequest.ofPairs(JournalDocType.JV, LocalDate.of(2026, 10, 5), "Survey fee",
                PostingRequest.Dimensions.ofProperty(property), JournalSourceType.MANUAL, null, null,
                List.of(PostingRequest.pair(PostingRequest.dr(AccountRole.BANK, new BigDecimal("10")),
                        PostingRequest.cr(AccountRole.OTHER_INCOME, new BigDecimal("10"))))));
        batches.setAcquisitionDate(batchId, A);
        assertThatThrownBy(() -> postService.post(batchId))
                .hasMessageContaining("only buildings with no postings yet")
                .hasMessageContaining("Acquired Tower");
        ImportBatchStatus status = tx.execute(s -> batches.get(batchId).getStatus());
        assertThat(status).isEqualTo(ImportBatchStatus.DRAFT);
    }

    /** A cheque the previous owner deposited that had not cleared by the acquisition: the sheet must say how it ended. */
    @Test
    void aChequeInFlightAtTheAcquisitionRefusesItsContract() throws Exception {
        UUID batchId = importAcquisition(wb -> {
            CutoverFixture.set(wb, "Cheques", 3, 10, "DEPOSITED");
            CutoverFixture.set(wb, "Cheques", 3, 11, "2026-10-05");
        });
        batches.setAcquisitionDate(batchId, A);
        BulkPostResult result = postService.post(batchId);
        assertThat(result.leasesPosted()).isEqualTo(1);
        assertThat(result.failures()).singleElement()
                .satisfies(f -> assertThat(f.getMessage()).contains("ACQ-0002").contains("had not cleared"));
    }

    /**
     * Without a date the batch is still the go-live cut-over's and is refused once the
     * opening balances are posted (the sim's case); discarding it removes the property,
     * units and draft contracts it created.
     */
    @Test
    void aRefusedBatchDiscardsItsDraftsAndUnits() throws Exception {
        UUID batchId = importAcquisition(null);
        long unitsBefore = tx.execute(s -> unitRepo.count());
        assertThatThrownBy(() -> postService.post(batchId)).hasMessageContaining("Opening balances are posted");

        discards.discard(batchId);

        long unitsAfter = tx.execute(s -> unitRepo.count());
        assertThat(unitsAfter).isEqualTo(unitsBefore - 2);
        long acquiredLeases = tx.execute(s -> leaseRepo.findAll().stream()
                .filter(l -> l.getExternalContractRef() != null && l.getExternalContractRef().startsWith("ACQ-"))
                .count());
        assertThat(acquiredLeases).isZero();
        assertThat(jdbc.queryForObject("select count(*) from properties where tenant_id = ? and name_en = 'Acquired Tower'",
                Integer.class, tenantId)).isZero();
    }

    // ------------------------------------------------------------------ fixture

    /** The template again, as "Acquired Tower": same renters (re-used), new contracts, units and cheque numbers. */
    private UUID importAcquisition(java.util.function.Consumer<Workbook> tweak) throws Exception {
        try (Workbook wb = fixture.template()) {
            CutoverFixture.set(wb, "Properties", 1, 0, "Acquired Tower");
            for (int c = 6; c <= 11; c++) CutoverFixture.set(wb, "Properties", 1, c, "");
            CutoverFixture.set(wb, "Units", 1, 0, "Acquired Tower");
            CutoverFixture.set(wb, "Units", 2, 0, "Acquired Tower");
            CutoverFixture.set(wb, "Contracts", 1, 1, "EJ-ACQ-0001");
            CutoverFixture.set(wb, "Contracts", 3, 1, "EJ-ACQ-0002");
            CutoverFixture.set(wb, "Contracts", 1, 2, "Acquired Tower");
            CutoverFixture.set(wb, "Contracts", 3, 2, "Acquired Tower");
            CutoverFixture.set(wb, "Cheques", 1, 3, "200001");
            CutoverFixture.set(wb, "Cheques", 2, 3, "200002");
            CutoverFixture.set(wb, "Cheques", 3, 3, "200003");
            if (tweak != null) tweak.accept(wb);
            // The contract references are the go-live ones' again, which the import
            // refuses; renamed on every sheet that names them.
            rename(wb, "Contracts", "SAMPLE-", "ACQ-");
            rename(wb, "Cheques", "SAMPLE-", "ACQ-");
            return contractPersist.persist(wb, fixture.newJob()).batchId();
        }
    }

    private static void rename(Workbook wb, String sheet, String from, String to) {
        var s = wb.getSheet(sheet);
        for (int r = 1; r <= s.getLastRowNum(); r++) {
            var cell = s.getRow(r) == null ? null : s.getRow(r).getCell(0);
            if (cell != null && cell.getStringCellValue().startsWith(from)) {
                cell.setCellValue(to + cell.getStringCellValue().substring(from.length()));
            }
        }
    }

    private UUID acquiredProperty() {
        return jdbc.queryForObject("select id from properties where tenant_id = ? and name_en = 'Acquired Tower'",
                UUID.class, tenantId);
    }

    private UUID leaseIdOf(String sampleRef) {
        String ref = sampleRef.replace("SAMPLE-", "ACQ-");
        return tx.execute(s -> leaseRepo.findAll().stream().filter(l -> ref.equals(l.getExternalContractRef()))
                .map(Lease::getId).findFirst().orElseThrow());
    }

    private List<Cheque> cheques(String sampleRef) {
        UUID leaseId = leaseIdOf(sampleRef);
        return tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId));
    }

    private long renterCount() {
        return tx.execute(s -> renterRepo.count());
    }

    private String obJournal() {
        return jdbc.queryForObject("""
                select string_agg(e.entry_number || ':' || e.status || ':' || l.debit || ':' || l.credit, ',' order by l.id)
                  from journal_entries e join journal_lines l on l.journal_entry_id = e.id
                 where e.tenant_id = ? and e.doc_type = 'OB'""", String.class, tenantId);
    }

    /** The property's balance on the role's account as at a date (debit − credit). */
    private BigDecimal balance(UUID property, AccountRole role, LocalDate asAt) {
        UUID account = tx.execute(s -> resolver.resolve(role, role.isPropertyScoped() ? property : null)).getId();
        return jdbc.queryForObject("""
                select coalesce(sum(l.debit - l.credit), 0) from journal_lines l
                  join journal_entries e on e.id = l.journal_entry_id
                 where l.tenant_id = ? and l.property_id = ? and l.account_id = ? and e.entry_date <= ?""",
                BigDecimal.class, tenantId, property, account, asAt);
    }
}
