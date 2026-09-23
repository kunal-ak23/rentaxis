package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.ImportBatch;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Reversing a whole cut-over import batch (spec §10.3, controller ruling R12).
 *
 * <p><b>Why the journals are dated inside the locked period.</b> That is the real
 * shape of a cut-over: the books open on 1 Oct and every contract imported was
 * signed before it. Import journals are exempt from the period lock, and so are
 * their reversals — {@code PostingService.reverse} applies the same
 * {@code importBatchId} test that {@code post} does. {@link
 * #aJournalThatIsNotPartOfABatchCannotBePostedIntoTheSameClosedPeriod} pins that
 * the lock really is on, so the exemption is the reason these pass rather than the
 * lock being misconfigured in the fixture.</p>
 *
 * <p><b>The lease side is a mock.</b> {@code LeaseReverter} is implemented by plan
 * 4 Task 11 on {@code LeaseService}; what matters here is that reverse asks for
 * every imported lease to go back to DRAFT, exactly once each, inside the same
 * transaction as the journal reversals.</p>
 *
 * <p>Read-backs go through {@link #tx}: {@code TenantAspect} only enables the
 * Hibernate tenant filter inside a transaction.</p>
 */
@SpringBootTest
class ImportBatchReverseIT extends AbstractPostgresIT {

    @Autowired ImportBatchService batches;
    @Autowired PostingService posting;
    @Autowired AccountService accounts;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired JournalEntryRepository entries;
    @Autowired LedgerQueryService ledger;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired TransactionTemplate tx;

    @MockitoBean LeaseReverter leaseReverter;

    UUID tenantId;
    Account receivable, advanceRent;

    private static final LocalDate IN_THE_CLOSED_PERIOD = LocalDate.of(2026, 9, 11);
    private static final LocalDate REVERSAL_DATE = LocalDate.of(2026, 9, 30);

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Batch-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        receivable = accounts.createLeaf("Rent Receivable - Tulip 7", accounts.getAccountByCode("A-02-01"), null);
        advanceRent = accounts.createLeaf("Advance Rent - Tulip 7", accounts.getAccountByCode("B-01-01"), null);
        fiscal.setBooksStartDate(LocalDate.of(2026, 10, 1));
        fiscal.lockThrough(LocalDate.of(2026, 9, 30));
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private JournalEntry importJournal(UUID batchId, String amount, LocalDate date) {
        return posting.post(new PostingRequest(
                JournalDocType.TCO, date, "Imported contract", PostingRequest.Dimensions.none(),
                JournalSourceType.IMPORT, UUID.randomUUID(), batchId,
                List.of(PostingRequest.dr(receivable.getId(), new BigDecimal(amount)),
                        PostingRequest.cr(advanceRent.getId(), new BigDecimal(amount)))));
    }

    private List<JournalEntry> batchJournals(UUID batchId) {
        return tx.execute(s -> entries.findByImportBatchIdOrderByCreatedAtAsc(batchId));
    }

    private JournalStatus statusOf(UUID entryId) {
        return tx.execute(s -> entries.findById(entryId).orElseThrow().getStatus());
    }

    private BigDecimal balanceOf(Account account) {
        return balanceAt(account, REVERSAL_DATE);
    }

    /** The same, as at any date — a reverse has to be flat on every one of them. */
    private BigDecimal balanceAt(Account account, LocalDate asOf) {
        return tx.execute(s -> ledger.trialBalance(asOf, null).stream()
                .filter(r -> r.accountId().equals(account.getId()))
                .map(TrialBalanceRowDTO::balance)
                .findFirst().orElse(BigDecimal.ZERO));
    }

    /** Every scenario ends here: the books still balance, whatever was taken off them. */
    private void assertTrialBalanceBalances() {
        List<TrialBalanceRowDTO> rows = tx.execute(s -> ledger.trialBalance(LocalDate.of(2030, 1, 1), null));
        BigDecimal debit = rows.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = rows.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).as("trial balance").isEqualByComparingTo(credit);
    }

    // ------------------------------------------------------------------
    // the undo
    // ------------------------------------------------------------------

    @Test
    void reversingABatchReversesEveryJournalAndReturnsEveryLeaseToDraft() {
        ImportBatch b = batches.create(null, "September cut-over");
        UUID leaseA = UUID.randomUUID(), leaseB = UUID.randomUUID();
        batches.linkLease(b.getId(), leaseA);
        batches.linkLease(b.getId(), leaseB);
        JournalEntry e1 = importJournal(b.getId(), "61000.00", IN_THE_CLOSED_PERIOD);
        JournalEntry e2 = importJournal(b.getId(), "45000.00", IN_THE_CLOSED_PERIOD.plusDays(1));
        batches.markPosted(b.getId(), 2);

        ImportBatch reversed = batches.reverse(b.getId(), "Re-import with corrected rents");

        assertThat(reversed.getStatus()).isEqualTo(ImportBatchStatus.REVERSED);
        assertThat(reversed.getReversedAt()).isNotNull();
        assertThat(statusOf(e1.getId())).isEqualTo(JournalStatus.REVERSED);
        assertThat(statusOf(e2.getId())).isEqualTo(JournalStatus.REVERSED);
        verify(leaseReverter).revertToDraft(leaseA);
        verify(leaseReverter).revertToDraft(leaseB);
        assertTrialBalanceBalances();
    }

    /** After a reverse, every account the batch touched nets to zero. */
    @Test
    void theLedgerIsFlatAfterReversingABatch() {
        ImportBatch b = batches.create(null, "cut-over");
        importJournal(b.getId(), "61000.00", IN_THE_CLOSED_PERIOD);
        batches.markPosted(b.getId(), 1);
        assertThat(balanceOf(receivable)).isEqualByComparingTo("61000.00");

        batches.reverse(b.getId(), "redo");

        assertThat(balanceOf(receivable)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(advanceRent)).isEqualByComparingTo("0.00");
        assertTrialBalanceBalances();
    }

    /** The mirror entries carry the batch id too, or a second reverse would miss them. */
    @Test
    void theReversingEntriesBelongToTheSameBatch() {
        ImportBatch b = batches.create(null, "cut-over");
        JournalEntry original = importJournal(b.getId(), "1000.00", IN_THE_CLOSED_PERIOD);
        batches.markPosted(b.getId(), 1);

        batches.reverse(b.getId(), "redo");

        List<JournalEntry> all = batchJournals(b.getId());
        assertThat(all).hasSize(2);
        assertThat(all).filteredOn(e -> e.getReversalOfId() != null).singleElement()
                .satisfies(mirror -> {
                    assertThat(mirror.getReversalOfId()).isEqualTo(original.getId());
                    assertThat(mirror.getSourceType()).isEqualTo(JournalSourceType.REVERSAL);
                    // A TCO's mirror is a TCR, and it is dated on the entry it mirrors —
                    // never on a day a caller picked (review C1, ruling R16).
                    assertThat(mirror.getDocType()).isEqualTo(JournalDocType.TCR);
                    assertThat(mirror.getEntryDate()).isEqualTo(IN_THE_CLOSED_PERIOD);
                });
        assertTrialBalanceBalances();
    }

    /**
     * Newest-first (R12). A CBR posted after a CRT for the same cheque has to come
     * off before the CRT does; the end state balances either way, so this asserts
     * the order the mirrors were written in rather than the totals.
     */
    @Test
    void theJournalsAreReversedNewestFirst() {
        ImportBatch b = batches.create(null, "cut-over");
        JournalEntry first = importJournal(b.getId(), "100.00", IN_THE_CLOSED_PERIOD);
        JournalEntry second = importJournal(b.getId(), "200.00", IN_THE_CLOSED_PERIOD);
        JournalEntry third = importJournal(b.getId(), "300.00", IN_THE_CLOSED_PERIOD);
        batches.markPosted(b.getId(), 3);

        batches.reverse(b.getId(), "redo");

        List<UUID> mirrorsInWriteOrder = batchJournals(b.getId()).stream()
                .filter(e -> e.getReversalOfId() != null)
                .sorted(java.util.Comparator.comparing(JournalEntry::getCreatedAt))
                .map(JournalEntry::getReversalOfId)
                .toList();
        assertThat(mirrorsInWriteOrder).containsExactly(third.getId(), second.getId(), first.getId());
        assertTrialBalanceBalances();
    }

    /**
     * A batch someone had already started to unwind by hand (R12). Both halves of
     * that pair have to be skipped: the original is REVERSED, and its mirror is
     * POSTED and carries the same {@code importBatchId} — {@code PostingService}
     * copies it — so it comes back from the same query. Reverse either and the
     * refusal aborts the whole batch, which is how a partial undo becomes an undo
     * nobody can finish.
     */
    @Test
    void aJournalAlreadyReversedByHandIsSkippedRatherThanReversedTwice() {
        ImportBatch b = batches.create(null, "cut-over");
        JournalEntry e1 = importJournal(b.getId(), "1000.00", IN_THE_CLOSED_PERIOD);
        JournalEntry e2 = importJournal(b.getId(), "2000.00", IN_THE_CLOSED_PERIOD);
        batches.markPosted(b.getId(), 2);
        posting.reverse(e1.getId(), REVERSAL_DATE, "taken off on its own");
        assertThat(batchJournals(b.getId())).as("two originals and one mirror").hasSize(3);

        batches.reverse(b.getId(), "redo");

        // Only e2 still needed a mirror.
        assertThat(batchJournals(b.getId())).as("one more mirror, not three").hasSize(4);
        assertThat(statusOf(e2.getId())).isEqualTo(JournalStatus.REVERSED);
        assertThat(balanceOf(receivable)).isEqualByComparingTo("0.00");
        assertTrialBalanceBalances();
    }

    @Test
    void reversingTwiceIsRejectedRatherThanDoublePosting() {
        ImportBatch b = batches.create(null, "cut-over");
        importJournal(b.getId(), "1000.00", IN_THE_CLOSED_PERIOD);
        batches.markPosted(b.getId(), 1);
        batches.reverse(b.getId(), "redo");

        assertThatThrownBy(() -> batches.reverse(b.getId(), "again"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("REVERSED");
        assertThat(batchJournals(b.getId())).hasSize(2);
        assertTrialBalanceBalances();
    }

    @Test
    void aDraftBatchHasNothingToReverse() {
        ImportBatch b = batches.create(null, "cut-over");
        assertThatThrownBy(() -> batches.reverse(b.getId(), "x"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("DRAFT");
        verify(leaseReverter, times(0)).revertToDraft(any());
    }

    /**
     * Review C1 / ruling R16: every mirror is dated on the entry it reverses, and
     * nothing of the undo lands in the open period.
     *
     * <p>The batch's two entries are on <em>different</em> days inside the locked
     * period, so one reversal date cannot be right for both. Before this fix the
     * caller supplied one and the web sent today: the books as at each entry's own
     * day still carried the whole cut-over ({@code balancesAsOf} has no status
     * predicate, so a REVERSED entry still counts at its own date) while the first
     * live month carried the undo of history that never belonged to it.</p>
     */
    @Test
    void everyMirrorIsDatedOnItsOwnEntryAndNothingOfTheUndoReachesTheOpenPeriod() {
        ImportBatch b = batches.create(null, "cut-over");
        JournalEntry early = importJournal(b.getId(), "1000.00", IN_THE_CLOSED_PERIOD);
        JournalEntry late = importJournal(b.getId(), "2000.00", IN_THE_CLOSED_PERIOD.plusDays(4));
        batches.markPosted(b.getId(), 2);

        batches.reverse(b.getId(), "redo");

        List<JournalEntry> mirrors = batchJournals(b.getId()).stream()
                .filter(e -> e.getReversalOfId() != null).toList();
        assertThat(mirrors).hasSize(2);
        assertThat(mirrors).filteredOn(m -> m.getReversalOfId().equals(early.getId()))
                .singleElement()
                .satisfies(m -> assertThat(m.getEntryDate()).isEqualTo(early.getEntryDate()));
        assertThat(mirrors).filteredOn(m -> m.getReversalOfId().equals(late.getId()))
                .singleElement()
                .satisfies(m -> assertThat(m.getEntryDate()).isEqualTo(late.getEntryDate()));

        // Flat on the day the FIRST entry was written, which is the assertion the old
        // behaviour failed: at that date the second entry has not happened yet and the
        // first one's mirror has to be there to cancel it.
        assertThat(balanceAt(receivable, IN_THE_CLOSED_PERIOD)).isEqualByComparingTo("0.00");
        assertThat(balanceAt(advanceRent, IN_THE_CLOSED_PERIOD)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(receivable)).isEqualByComparingTo("0.00");
        // And nothing of the reverse is dated into the open period.
        assertThat(batchJournals(b.getId()))
                .allSatisfy(e -> assertThat(e.getEntryDate()).isBeforeOrEqualTo(REVERSAL_DATE));
        assertTrialBalanceBalances();
    }

    /** A batch of opening balances alone has no leases, and reverses without a lease module. */
    @Test
    void aBatchWithNoLeasesReversesWithoutTouchingTheLeaseModule() {
        ImportBatch b = batches.create(null, "opening balances only");
        importJournal(b.getId(), "5000.00", IN_THE_CLOSED_PERIOD);
        batches.markPosted(b.getId(), 1);

        ImportBatch reversed = batches.reverse(b.getId(), "redo");

        assertThat(reversed.getStatus()).isEqualTo(ImportBatchStatus.REVERSED);
        verifyNoInteractions(leaseReverter);
        assertTrialBalanceBalances();
    }

    /**
     * One transaction (R12). A lease that refuses to go back to DRAFT must leave the
     * journals on the books: the half-done state — journals reversed, leases still
     * posted — is the one the books cannot explain afterwards, and it is exactly what
     * an accountant would find if reverse were a loop of independent transactions.
     */
    @Test
    void aFailingLeaseRevertRollsTheWholeReverseBack() {
        ImportBatch b = batches.create(null, "cut-over");
        UUID leaseA = UUID.randomUUID();
        batches.linkLease(b.getId(), leaseA);
        JournalEntry e1 = importJournal(b.getId(), "61000.00", IN_THE_CLOSED_PERIOD);
        batches.markPosted(b.getId(), 1);
        doThrow(new IllegalStateException("lease module said no")).when(leaseReverter).revertToDraft(leaseA);

        assertThatThrownBy(() -> batches.reverse(b.getId(), "redo"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(statusOf(e1.getId())).as("the journal was not reversed").isEqualTo(JournalStatus.POSTED);
        assertThat(batchJournals(b.getId())).as("no mirror entry was written").hasSize(1);
        assertThat(batches.get(b.getId()).getStatus()).isEqualTo(ImportBatchStatus.POSTED);
        assertThat(balanceOf(receivable)).isEqualByComparingTo("61000.00");
        assertTrialBalanceBalances();
    }

    /**
     * Two clerks (or one double-click) reversing one batch must take it off once.
     * The row lock decides it; the loser re-reads REVERSED and refuses.
     */
    @Test
    void twoSimultaneousReversesLeaveOneMirrorPerJournal() throws Exception {
        ImportBatch b = batches.create(null, "cut-over");
        importJournal(b.getId(), "1000.00", IN_THE_CLOSED_PERIOD);
        importJournal(b.getId(), "2000.00", IN_THE_CLOSED_PERIOD);
        batches.markPosted(b.getId(), 2);
        UUID batchId = b.getId();
        UUID tenant = tenantId;

        CyclicBarrier bothReady = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            racers.add(() -> {
                TenantContextHolder.setTenantId(tenant);
                try {
                    bothReady.await(10, TimeUnit.SECONDS);
                    return batches.reverse(batchId, "redo");
                } catch (RuntimeException ex) {
                    return ex;
                } finally {
                    TenantContextHolder.clear();
                }
            });
        }
        List<Object> outcomes = new ArrayList<>();
        try {
            for (Future<Object> f : pool.invokeAll(racers, 60, TimeUnit.SECONDS)) outcomes.add(f.get());
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes).filteredOn(ImportBatch.class::isInstance).as("winners").hasSize(1);
        assertThat(outcomes).filteredOn(o -> !(o instanceof ImportBatch)).as("losers")
                .allMatch(BusinessRuleViolationException.class::isInstance);
        // Two originals and two mirrors — never four mirrors.
        assertThat(batchJournals(batchId)).hasSize(4);
        assertThat(balanceOf(receivable)).isEqualByComparingTo("0.00");
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // links, marking, tenancy
    // ------------------------------------------------------------------

    @Test
    void linkingTheSameLeaseTwiceCountsItOnce() {
        ImportBatch b = batches.create(null, "cut-over");
        UUID leaseA = UUID.randomUUID();
        batches.linkLease(b.getId(), leaseA);
        batches.linkLease(b.getId(), leaseA);
        batches.linkLease(b.getId(), UUID.randomUUID());

        assertThat(batches.leaseIds(b.getId())).hasSize(2).contains(leaseA);
        assertThat(batches.get(b.getId()).getLeasesImported()).isEqualTo(2);
    }

    /** A reversed batch is history; a corrected file is imported as a new batch. */
    @Test
    void aReversedBatchCannotBeMarkedPostedAgain() {
        ImportBatch b = batches.create(null, "cut-over");
        importJournal(b.getId(), "1000.00", IN_THE_CLOSED_PERIOD);
        batches.markPosted(b.getId(), 1);
        batches.reverse(b.getId(), "redo");

        assertThatThrownBy(() -> batches.markPosted(b.getId(), 1))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("REVERSED");
        assertThat(batches.get(b.getId()).getStatus()).isEqualTo(ImportBatchStatus.REVERSED);
    }

    /** Who did it, on both halves — the audit columns the batches screen shows. */
    @Test
    void theBatchRecordsWhoPostedAndWhoReversedIt() {
        UUID userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_ACCOUNTANT"))));

        ImportBatch b = batches.create(null, "cut-over");
        importJournal(b.getId(), "1000.00", IN_THE_CLOSED_PERIOD);
        assertThat(batches.markPosted(b.getId(), 1).getPostedBy()).isEqualTo(userId);
        assertThat(batches.reverse(b.getId(), "redo").getReversedBy()).isEqualTo(userId);
    }

    /**
     * P0. {@code lockForRun} loads the row with {@code EntityManager.find}, which
     * {@code TenantAspect} does not cover — the aspect enables the Hibernate filter
     * around {@code domain.repository..*} calls only, and none has run in the
     * transaction yet. The explicit tenant comparison is therefore the only guard on
     * the reverse path, which is why it is asserted rather than assumed. Reading and
     * post-marking go through the repository and are covered by the filter; all
     * three answer 404, because whether another organisation's id exists is not this
     * tenant's business.
     */
    @Test
    void tenantBCannotReadPostMarkOrReverseAnotherTenantsBatch() {
        ImportBatch b = batches.create(null, "Tenant A's cut-over");
        batches.linkLease(b.getId(), UUID.randomUUID());
        JournalEntry e1 = importJournal(b.getId(), "61000.00", IN_THE_CLOSED_PERIOD);
        batches.markPosted(b.getId(), 1);
        UUID batchId = b.getId();

        LandlordOrg orgB = new LandlordOrg();
        orgB.setName("Batch-B-" + UUID.randomUUID());
        TenantContextHolder.setTenantId(orgRepo.save(orgB).getId());

        assertThatThrownBy(() -> batches.get(batchId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> batches.leaseIds(batchId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> batches.markPosted(batchId, 9)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> batches.reverse(batchId, "not mine"))
                .isInstanceOf(NotFoundException.class);
        assertThat(batches.list()).as("tenant B's own batches").isEmpty();

        TenantContextHolder.setTenantId(tenantId);
        assertThat(batches.get(batchId).getStatus()).isEqualTo(ImportBatchStatus.POSTED);
        assertThat(statusOf(e1.getId())).isEqualTo(JournalStatus.POSTED);
        verifyNoInteractions(leaseReverter);
    }

    /**
     * The fixture's period lock really is on — so the batch reversals above pass
     * because of the {@code importBatchId} exemption, not because nothing was
     * locked.
     */
    @Test
    void aJournalThatIsNotPartOfABatchCannotBePostedIntoTheSameClosedPeriod() {
        assertThatThrownBy(() -> posting.post(new PostingRequest(
                JournalDocType.JV, IN_THE_CLOSED_PERIOD, "manual", PostingRequest.Dimensions.none(),
                JournalSourceType.MANUAL, UUID.randomUUID(), null,
                List.of(PostingRequest.dr(receivable.getId(), new BigDecimal("10.00")),
                        PostingRequest.cr(advanceRent.getId(), new BigDecimal("10.00"))))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("books are locked through");
    }
}
