package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.AddChargeRequest;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.ExtendLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.api.dto.vat.TaxInvoiceDTO;
import com.datagami.rentaxis.api.dto.vat.VatTaxPointDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.cheque.ChequeDetailsService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.lease.LeaseRenewalService;
import com.datagami.rentaxis.core.service.lease.LeaseTerminationService;
import com.datagami.rentaxis.core.service.lease.LeaseVariationService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.TaxInvoiceKind;
import com.datagami.rentaxis.domain.entity.enums.VatTaxPointStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.vatLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The PR #348 review's findings on VAT per instalment, one test (or two) each.
 *
 * <p>The spine is the spec's worked example again: 120,000 + VAT, TCO 20/04/2026,
 * term 01/05/2026 – 30/04/2027, four cheques of 31,500 (1,500 VAT each) on 01/05,
 * 01/08, 01/11 and 01/02.</p>
 */
@SpringBootTest
class VatReviewFixesIT extends AbstractPostgresIT {

    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired ChequeService chequeService;
    @Autowired ChequeDetailsService chequeDetails;
    @Autowired LeaseTerminationService termination;
    @Autowired LeaseRenewalService renewal;
    @Autowired LeaseVariationService variations;
    @Autowired VatTaxPointService vatTaxPoints;
    @Autowired VatTaxPointPoster poster;
    @Autowired TaxInvoiceService taxInvoices;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT = LocalDate.of(2026, 4, 20);
    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate END = LocalDate.of(2027, 4, 30);
    private static final LocalDate MAY = LocalDate.of(2026, 5, 1);
    private static final LocalDate AUG = LocalDate.of(2026, 8, 1);
    private static final LocalDate NOV = LocalDate.of(2026, 11, 1);
    private static final LocalDate FEB = LocalDate.of(2027, 2, 1);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private UUID workedExample() {
        return fixtures.postedLease(CONTRACT, START, END, List.of(vatLine("RENT", "120000")), 4, null).lease().getId();
    }

    private List<VatTaxPointDTO> schedule(UUID leaseId) {
        return vatTaxPoints.scheduleFor(leaseId);
    }

    private List<ChequeDTO> register(UUID leaseId) {
        return chequeGeneration.list(leaseId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private UUID pointOf(UUID chequeId) {
        return jdbc.queryForObject("select id from vat_tax_points where cheque_id = ? and status <> 'CANCELLED'",
                UUID.class, chequeId);
    }

    /** Runs {@code body} on a worker thread with this test's tenant and a TENANT_ADMIN. */
    private Thread worker(AtomicReference<Throwable> failure, Runnable body) {
        UUID tenant = fixtures.tenantId();
        Thread t = new Thread(() -> {
            TenantContextHolder.setTenantId(tenant);
            LeaseTestFixtures.authenticateAsTenantAdmin();
            try {
                body.run();
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                TenantContextHolder.clear();
                LeaseTestFixtures.clearAuth();
            }
        });
        t.start();
        return t;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new AssertionError("timed out");
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------
    // P2-1: a writer that does not lock can undo a POSTED point
    // ------------------------------------------------------------------

    /**
     * The review's scenario 1, as a real race. Thread B posts May's point and holds
     * its transaction open; thread A moves May's cheque date meanwhile. A must wait
     * for B's lock, then see POSTED and leave the point alone — not write it back to
     * PLANNED (which the next run would post again: 1,500 declared twice), and not
     * fail on the version check either.
     */
    @Test
    void aDateChangeRacingThePostOfItsPointLeavesThePointPosted() throws Exception {
        UUID leaseId = workedExample();
        UUID mayCheque = register(leaseId).get(0).id();
        UUID mayPoint = pointOf(mayCheque);

        CountDownLatch posted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> bFailed = new AtomicReference<>();
        AtomicReference<Throwable> aFailed = new AtomicReference<>();

        Thread b = worker(bFailed, () -> tx.executeWithoutResult(s -> {
            poster.postJoining(mayPoint);
            posted.countDown();
            await(release);
        }));
        await(posted);
        Thread a = worker(aFailed, () -> chequeDetails.updateDetails(mayCheque, new ChequeRowInput(null, null, null,
                null, LocalDate.of(2026, 5, 9), null, null, null, null, null, null)));
        // A is now blocked on B's row lock (or about to be); let B commit.
        Thread.sleep(1500);
        release.countDown();
        b.join(30_000);
        a.join(30_000);

        assertThat(bFailed.get()).isNull();
        assertThat(aFailed.get()).as("the date change goes through once the post has committed").isNull();
        assertThat(jdbc.queryForObject("select status from vat_tax_points where id = ?", String.class, mayPoint))
                .isEqualTo("POSTED");
        assertThat(jdbc.queryForObject("select tax_point_date from vat_tax_points where id = ?", LocalDate.class, mayPoint))
                .as("declared: stays where it was").isEqualTo(MAY);
        assertThat(jdbc.queryForObject("select journal_id from vat_tax_points where id = ?", UUID.class, mayPoint))
                .isNotNull();

        vatTaxPoints.runTo(MAY, false);
        assertThat(count("select count(*) from journal_entries where lease_id = ? and doc_type = 'VTP'", leaseId))
                .as("May's VAT is declared once").isEqualTo(1);
    }

    /** The job's own re-check: a point that is no longer PLANNED, or no longer due, is left alone. */
    @Test
    void theJobPostsNothingForAPointThatIsNoLongerDue() {
        UUID leaseId = workedExample();
        UUID augPoint = pointOf(register(leaseId).get(1).id());

        assertThat(poster.post(augPoint, LocalDate.of(2026, 7, 31))).as("dated after the run's date").isNull();
        assertThat(poster.post(augPoint, AUG)).isNotNull();
        assertThat(poster.post(augPoint, AUG)).as("already POSTED").isNull();
        assertThat(count("select count(*) from journal_entries where lease_id = ? and doc_type = 'VTP'", leaseId))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // P2-2: no PLANNED point inside the locked period
    // ------------------------------------------------------------------

    /** Books locked through 30/06: May's point is posted, and the lock goes on. */
    private UUID lockedThroughJune() {
        UUID leaseId = workedExample();
        vatTaxPoints.runTo(LocalDate.of(2026, 6, 30), false);
        fiscal.lockThrough(LocalDate.of(2026, 6, 30));
        return leaseId;
    }

    /** The review's scenario: an addendum on 10/07 whose cheque is dated 01/06. */
    @Test
    void anAddendumRowWhoseTaxPointFallsInTheLockedPeriodIsRefused() {
        UUID leaseId = lockedThroughJune();
        ChequeRowInput june = LeaseTestFixtures.chequeRow("1050", LocalDate.of(2026, 6, 1));

        // Refused up front with the other problems ("Cheque …"), not by the schedule
        // builder's backstop ("Instalment …") after the rows are written.
        assertThatThrownBy(() -> variations.addCharge(leaseId, new AddChargeRequest(LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 7, 10), null, "parking",
                List.of(vatLine("PARKING_FEE", "1000")), List.of(june))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cheque " + june.chequeNumber()
                        + "'s VAT tax point (2026-06-01) falls in a locked period: books are locked through 2026-06-30");
        assertThat(schedule(leaseId)).noneMatch(p -> p.taxPointDate().equals(LocalDate.of(2026, 6, 1)));

        // Dated after the lock, the same addendum posts and gets its point.
        variations.addCharge(leaseId, new AddChargeRequest(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 10),
                null, "parking", List.of(vatLine("PARKING_FEE", "1000")),
                List.of(LeaseTestFixtures.chequeRow("1050", LocalDate.of(2026, 7, 15)))));
        assertThat(schedule(leaseId)).anySatisfy(p -> {
            assertThat(p.taxPointDate()).isEqualTo(LocalDate.of(2026, 7, 15));
            assertThat(p.vatAmount()).isEqualByComparingTo("50.00");
        });
    }

    @Test
    void anExtensionRowWhoseTaxPointFallsInTheLockedPeriodIsRefused() {
        UUID leaseId = lockedThroughJune();

        ChequeRowInput june = LeaseTestFixtures.chequeRow("31500", LocalDate.of(2026, 6, 20));
        assertThatThrownBy(() -> renewal.extend(leaseId, new ExtendLeaseRequest(LocalDate.of(2027, 7, 31),
                LocalDate.of(2026, 7, 10), List.of(vatLine("RENT", "30000")), List.of(june))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cheque " + june.chequeNumber() + "'s VAT tax point (2026-06-20) falls in a locked period");
    }

    /** Moving a cancelled row's VAT onto an instalment dated in the locked period would strand it. */
    @Test
    void vatCannotBeMovedOntoAnInstalmentInTheLockedPeriod() {
        UUID leaseId = lockedThroughJune();
        List<ChequeDTO> rows = register(leaseId);

        assertThatThrownBy(() -> chequeService.cancel(rows.get(3).id(), null, rows.get(0).id()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("falls in a locked period: books are locked through 2026-06-30");
        assertThat(register(leaseId).get(3).status().name()).isEqualTo("REGISTERED");
    }

    /**
     * Prevention makes this unreachable; a point stranded there anyway (written
     * straight into the table here) gives the termination a sentence to act on, not
     * the ledger's generic period-lock refusal halfway through.
     */
    @Test
    void terminatingWithAnUndeclaredPointInTheLockedPeriodSaysWhatToDo() {
        UUID leaseId = lockedThroughJune();
        UUID augPoint = pointOf(register(leaseId).get(1).id());
        jdbc.update("update vat_tax_points set tax_point_date = ? where id = ?", LocalDate.of(2026, 6, 15), augPoint);

        assertThatThrownBy(() -> termination.terminate(leaseId,
                new TerminateLeaseRequest(LocalDate.of(2026, 10, 31), null, null, null), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("VAT of 1500.00 on 2026-06-15 has not been declared and falls in the locked period")
                .hasMessageContaining("reopen that period");
        assertThat(jdbc.queryForObject("select status from leases where id = ?", String.class, leaseId))
                .isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------
    // P2-3: no VAT on a deposit row
    // ------------------------------------------------------------------

    /**
     * The review's case: 120,000 + VAT with a 10,000 security deposit on a row of its
     * own, every row typed without a VAT figure. The deposit row gets none (pro rata
     * by amount used to hand it 441.18) and never gets a tax invoice; the four rent
     * rows carry 1,500 each.
     */
    @Test
    void aDepositRowNeverCarriesVatOrGetsATaxInvoice() {
        UUID leaseId = fixtures.draftLease(CONTRACT, START, END,
                List.of(vatLine("RENT", "120000"), line("SECURITY_DEPOSIT", "10000")));
        List<ChequeDTO> saved = chequeGeneration.saveRows(leaseId, List.of(
                LeaseTestFixtures.chequeRow("10000", CONTRACT),
                LeaseTestFixtures.chequeRow("31500", MAY),
                LeaseTestFixtures.chequeRow("31500", AUG),
                LeaseTestFixtures.chequeRow("31500", NOV),
                LeaseTestFixtures.chequeRow("31500", FEB)));

        assertThat(saved).extracting(ChequeDTO::vatAmount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("0.00"), new BigDecimal("1500.00"), new BigDecimal("1500.00"),
                        new BigDecimal("1500.00"), new BigDecimal("1500.00"));
        UUID depositRow = saved.get(0).id();

        posting.post(leaseId);
        vatTaxPoints.runTo(FEB, false);

        assertThat(schedule(leaseId)).hasSize(4).noneMatch(p -> depositRow.equals(p.chequeId()));
        assertThat(taxInvoices.forLease(leaseId)).hasSize(4).noneMatch(i -> depositRow.equals(i.chequeId()));
        assertThat(count("select count(*) from tax_invoices where cheque_id = ?", depositRow)).isZero();
    }

    /** The deposit folded into cheque 1, as the generator writes it: only cheque 1's rent part carries VAT. */
    @Test
    void aGridSavedWithoutVatAndADepositFoldedIntoTheFirstChequeWeighsOnlyItsRent() {
        UUID leaseId = fixtures.draftLease(CONTRACT, START, END,
                List.of(vatLine("RENT", "120000"), line("SECURITY_DEPOSIT", "10000")));
        List<ChequeDTO> saved = chequeGeneration.saveRows(leaseId, List.of(
                LeaseTestFixtures.chequeRow("41500", MAY),
                LeaseTestFixtures.chequeRow("31500", AUG),
                LeaseTestFixtures.chequeRow("31500", NOV),
                LeaseTestFixtures.chequeRow("31500", FEB)));

        assertThat(saved).extracting(ChequeDTO::vatAmount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1500.00"), new BigDecimal("1500.00"), new BigDecimal("1500.00"),
                        new BigDecimal("1500.00"));
    }

    /**
     * An amendment that halves the contract's VAT re-spreads it over the rows that
     * carried it, in the same proportions — the deposit row stays at zero. The
     * contract value is unchanged (the grid is the same paper): 63,000 of rent with
     * VAT, 63,000 of a fee without, and the deposit.
     */
    @Test
    void anAmendmentReSpreadsVatOnlyOverTheRowsThatCarryIt() {
        UUID leaseId = fixtures.draftLease(CONTRACT, START, END,
                List.of(vatLine("RENT", "120000"), line("SECURITY_DEPOSIT", "10000")));
        UUID depositRow = chequeGeneration.saveRows(leaseId, List.of(
                LeaseTestFixtures.chequeRow("10000", CONTRACT),
                LeaseTestFixtures.chequeRow("31500", MAY),
                LeaseTestFixtures.chequeRow("31500", AUG),
                LeaseTestFixtures.chequeRow("31500", NOV),
                LeaseTestFixtures.chequeRow("31500", FEB))).get(0).id();
        posting.post(leaseId);

        posting.amendLines(leaseId, List.of(vatLine("RENT", "60000"), line("ADMIN_FEE", "63000"),
                line("SECURITY_DEPOSIT", "10000")), "re-price");

        List<ChequeDTO> rows = register(leaseId);
        assertThat(rows).filteredOn(c -> c.id().equals(depositRow)).singleElement()
                .satisfies(c -> assertThat(c.vatAmount()).isEqualByComparingTo("0"));
        assertThat(rows).filteredOn(c -> !c.id().equals(depositRow))
                .allSatisfy(c -> assertThat(c.vatAmount()).isEqualByComparingTo("750.00"));
        assertThat(schedule(leaseId)).filteredOn(p -> p.status() == VatTaxPointStatus.PLANNED).hasSize(4)
                .noneMatch(p -> depositRow.equals(p.chequeId()));
    }

    // ------------------------------------------------------------------
    // P2-4: a contract with no VAT posts no row VAT
    // ------------------------------------------------------------------

    /**
     * The review's scenario: a grid generated for 120,000 + VAT, then the lines
     * edited to 126,000 with no VAT. Saving the grid back with its old VAT figures
     * zeroes them; a grid that still carries VAT (written straight into the table
     * here) is refused at post with a sentence, and no tax point is ever planned.
     */
    @Test
    void aContractWithNoVatCannotPostRowsThatCarryVat() {
        UUID leaseId = fixtures.draftLease(CONTRACT, START, END, List.of(vatLine("RENT", "120000")));
        List<ChequeDTO> grid = fixtures.generateGrid(leaseId, 4, START);
        assertThat(grid).allSatisfy(c -> assertThat(c.vatAmount()).isEqualByComparingTo("1500.00"));

        // The lines change under the grid (updateDraftLease would drop the grid, so
        // the lines are replaced directly, as a caller that keeps the rows does).
        tx.executeWithoutResult(s -> {
            var lease = leaseRepo.findById(leaseId).orElseThrow();
            leaseService.applyLines(lease, List.of(line("RENT", "126000")));
            leaseService.syncDerivedTotals(lease);
            leaseRepo.save(lease);
        });
        List<ChequeDTO> resaved = chequeGeneration.saveRows(leaseId, grid.stream().map(c -> new ChequeRowInput(c.id(),
                null, c.postingDate(), c.chequeNumber(), c.chequeDate(), c.payeeBank(), null, null, c.amount(),
                c.narration(), c.mode(), c.vatAmount())).toList());
        assertThat(resaved).allSatisfy(c -> assertThat(c.vatAmount()).isEqualByComparingTo("0"));

        jdbc.update("update cheques set vat_amount = 1500 where lease_id = ?", leaseId);
        assertThatThrownBy(() -> posting.post(leaseId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("The cheque grid carries VAT of 6,000.00 but the contract charges none");
        assertThat(count("select count(*) from vat_tax_points where lease_id = ?", leaseId)).isZero();
    }

    // ------------------------------------------------------------------
    // P3-2: appendRows validates the VAT it is given
    // ------------------------------------------------------------------

    @Test
    void anExtensionRowsVatMustBeBetweenZeroAndItsAmountAndAddUpToTheNewLines() {
        UUID leaseId = workedExample();
        LocalDate newEnd = LocalDate.of(2027, 7, 31);
        LocalDate may27 = LocalDate.of(2027, 5, 1);

        assertThatThrownBy(() -> renewal.extend(leaseId, new ExtendLeaseRequest(newEnd, LocalDate.of(2027, 4, 15),
                List.of(vatLine("RENT", "30000")), List.of(vatRow("31500", may27, "-1.00")))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("must be between zero and the row amount");
        assertThatThrownBy(() -> renewal.extend(leaseId, new ExtendLeaseRequest(newEnd, LocalDate.of(2027, 4, 15),
                List.of(vatLine("RENT", "30000")),
                List.of(vatRow("15750", may27, "1500.00"), vatRow("15750", LocalDate.of(2027, 6, 1), "750.00")))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("The new rows name VAT of 2250.00 but the new lines charge 1500.00");
        // Claiming less, with no row left to take the rest, strands it: refused too.
        assertThatThrownBy(() -> renewal.extend(leaseId, new ExtendLeaseRequest(newEnd, LocalDate.of(2027, 4, 15),
                List.of(vatLine("RENT", "30000")), List.of(vatRow("31500", may27, "1000.00")))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("The new rows carry VAT of 1,000.00 but the new lines charge 1,500.00");
        assertThat(schedule(leaseId)).hasSize(4);
    }

    // ------------------------------------------------------------------
    // P3-3: lockThrough and a new tax point cannot pass each other
    // ------------------------------------------------------------------

    /**
     * Thread A moves August's cheque to 15/07 and holds its transaction open; thread
     * B locks the books through 31/07 meanwhile. B must wait for A and then refuse
     * — the point it would otherwise strand is A's, committed after B looked.
     */
    @Test
    void aLockCannotSlipPastATaxPointBeingMovedIntoItsPeriod() throws Exception {
        UUID leaseId = workedExample();
        vatTaxPoints.runTo(LocalDate.of(2026, 7, 31), false);
        UUID augCheque = register(leaseId).get(1).id();

        CountDownLatch moved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> aFailed = new AtomicReference<>();
        AtomicReference<Throwable> bFailed = new AtomicReference<>();

        Thread a = worker(aFailed, () -> tx.executeWithoutResult(s -> {
            chequeDetails.updateDetails(augCheque, new ChequeRowInput(null, null, null, null,
                    LocalDate.of(2026, 7, 15), null, null, null, null, null, null));
            moved.countDown();
            await(release);
        }));
        await(moved);
        Thread b = worker(bFailed, () -> fiscal.lockThrough(LocalDate.of(2026, 7, 31)));
        Thread.sleep(1500);
        release.countDown();
        a.join(30_000);
        b.join(30_000);

        assertThat(aFailed.get()).isNull();
        assertThat(bFailed.get()).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Post the VAT tax points through 2026-07-31 first");
        assertThat(fiscal.get().getBooksLockedThrough()).isNull();
    }

    // ------------------------------------------------------------------
    // P3-4: the TRN cleared after posting
    // ------------------------------------------------------------------

    /**
     * The organisation's TRN is cleared after the lease posted. The nightly run still
     * posts the tax points, and the termination still issues its tax invoice, each
     * carrying the TRN the lease posted under — neither fails on a setting changed
     * after the fact.
     */
    @Test
    void aTrnClearedAfterPostingDoesNotStopTheTaxPointsOrATermination() {
        UUID leaseId = workedExample();
        String snapshot = tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow().getVatTrn());
        assertThat(snapshot).isEqualTo(LeaseTestFixtures.FIXTURE_TRN);
        tx.executeWithoutResult(s -> {
            LandlordOrg org = orgRepo.findById(fixtures.tenantId()).orElseThrow();
            org.setTrn(null);
            orgRepo.save(org);
        });

        assertThat(vatTaxPoints.runTo(AUG, false).errors()).isEmpty();
        termination.terminate(leaseId, new TerminateLeaseRequest(LocalDate.of(2026, 10, 31), null, null, null), null);

        List<String> trns = jdbc.queryForList("select supplier_trn from tax_invoices where lease_id = ?", String.class,
                leaseId);
        assertThat(trns).hasSize(3).containsOnly(LeaseTestFixtures.FIXTURE_TRN);
    }

    // ------------------------------------------------------------------
    // P3-6: a credit note names what it adjusts
    // ------------------------------------------------------------------

    /**
     * U > P: May, August and November declared (4,500), February pending (P = 1,500),
     * T = 31/10/2026, U = 2,975.34. The credit note for 1,475.34 adjusts the invoice
     * whose period runs past T — November's — and says so, by number and date.
     */
    @Test
    void aTerminationCreditNoteNamesTheInvoicesItAdjusts() {
        UUID leaseId = workedExample();
        vatTaxPoints.runTo(NOV, false);
        List<TaxInvoiceDTO> issued = taxInvoices.forLease(leaseId);
        assertThat(issued).hasSize(3);
        String august = issued.get(1).invoiceNumber();
        String november = issued.get(2).invoiceNumber();

        termination.terminate(leaseId, new TerminateLeaseRequest(LocalDate.of(2026, 10, 31), null, null, null), null);

        List<TaxInvoiceDTO> after = taxInvoices.forLease(leaseId);
        TaxInvoiceDTO credit = after.stream().filter(i -> i.kind() == TaxInvoiceKind.CREDIT_NOTE)
                .findFirst().orElseThrow();
        assertThat(credit.vatAmount()).isEqualByComparingTo("1475.34");
        String reference = jdbc.queryForObject("select reference_note from tax_invoices where id = ?", String.class,
                credit.id());
        assertThat(reference).isEqualTo(november + " (01/11/2026)").doesNotContain(august);
        assertThat(jdbc.queryForObject("select reference_note from tax_invoices where lease_id = ? and invoice_number = ?",
                String.class, leaseId, november)).as("a tax invoice references nothing").isNull();

        String pdfText = pdfText(taxInvoices.pdf(credit.id()).bytes());
        assertThat(pdfText).contains("Adjusts tax invoice").contains(november);
    }

    private static String pdfText(byte[] pdf) {
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static ChequeRowInput vatRow(String amount, LocalDate date, String vat) {
        return new ChequeRowInput(null, null, null, LeaseTestFixtures.nextChequeNumber(), date, "Emirates NBD", null,
                null, new BigDecimal(amount), null, null, new BigDecimal(vat));
    }
}
