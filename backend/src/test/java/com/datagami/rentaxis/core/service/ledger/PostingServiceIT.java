package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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

import static com.datagami.rentaxis.core.service.ledger.PostingRequest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class PostingServiceIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired PostingService posting;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired JournalEntryRepository entries;
    @Autowired JournalLineRepository lines;
    @Autowired PropertyAccountMappingRepository propertyMappings;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired AccountRepository accountRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.datagami.rentaxis.core.service.cutover.ImportBatchService batches;

    UUID tenantId; UUID propertyId;
    Account rentRecvLeaf, advanceRentLeaf, bankLeaf;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg(); org.setName("Post-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        propertyAccounts.seedDefaultTemplateAndDefaults();
        Property p = new Property(); p.setNameEn("Sample Heights"); p.setEmirate(Emirate.DUBAI);
        propertyId = propertyRepo.save(p).getId();
        rentRecvLeaf = accounts.createLeaf("Rent Receivable - Sample Heights", accounts.getAccountByCode("A-02-01"), propertyId);
        advanceRentLeaf = accounts.createLeaf("Advance Rent - Sample Heights", accounts.getAccountByCode("B-01-01"), propertyId);
        bankLeaf = accounts.createLeaf("Emirates Islamic - Sample Heights", accounts.getAccountByCode("A-02-02"), propertyId);
        map(propertyId, AccountRole.RENT_RECEIVABLE, rentRecvLeaf);
        map(propertyId, AccountRole.ADVANCE_RENT, advanceRentLeaf);
        map(propertyId, AccountRole.BANK, bankLeaf);
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    private void map(UUID prop, AccountRole role, Account a) {
        PropertyAccountMapping m = new PropertyAccountMapping(); m.setPropertyId(prop); m.setRole(role); m.setAccount(a);
        propertyMappings.save(m);
    }

    private long journalEntryRows() {
        return jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ?", Long.class, tenantId);
    }

    private PostingRequest contract(BigDecimal amount) {
        return new PostingRequest(JournalDocType.TCO, LocalDate.of(2026, 9, 11), "Contract Sample Heights SMH-324",
                Dimensions.ofProperty(propertyId), JournalSourceType.LEASE, UUID.randomUUID(), null,
                List.of(dr(AccountRole.RENT_RECEIVABLE, amount), cr(AccountRole.ADVANCE_RENT, amount)));
    }

    @Test
    void postsBalancedEntryWithResolvedAccountsAndNumber() {
        JournalEntry e = posting.post(contract(new BigDecimal("61000.00")));
        assertThat(e.getEntryNumber()).isEqualTo("TCO-26/1");
        assertThat(e.getStatus()).isEqualTo(JournalStatus.POSTED);
        List<JournalLine> ls = lines.findByEntry_IdOrderByLineNoAsc(e.getId());
        assertThat(ls).hasSize(2);
        assertThat(ls.get(0).getAccountId()).isEqualTo(rentRecvLeaf.getId());
        assertThat(ls.get(0).getDebit()).isEqualByComparingTo("61000.00");
        assertThat(ls.get(1).getAccountId()).isEqualTo(advanceRentLeaf.getId());
        assertThat(ls.get(1).getCredit()).isEqualByComparingTo("61000.00");
        assertThat(ls.get(0).getPropertyId()).isEqualTo(propertyId); // header dims copied to lines
    }

    @Test
    void rejectsUnbalancedBeforeTouchingTheDatabase() {
        PostingRequest bad = new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 9, 11), "bad", Dimensions.ofProperty(propertyId),
                JournalSourceType.MANUAL, null, null,
                List.of(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("100")), cr(AccountRole.ADVANCE_RENT, new BigDecimal("99"))));
        assertThatThrownBy(() -> posting.post(bad)).isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("not balanced");
        // Counted in SQL, scoped to this test's tenant: the Hibernate tenant filter is
        // enabled by an aspect on the repository call, and outside a transaction that
        // session is discarded before the query runs, so entries.count() would answer
        // for every tenant the shared container has ever seen.
        assertThat(journalEntryRows()).isZero();
    }

    @Test
    void rejectsZeroNegativeAndSingleLine() {
        assertThatThrownBy(() -> posting.post(contract(BigDecimal.ZERO))).hasMessageContaining("positive");
        assertThatThrownBy(() -> posting.post(contract(new BigDecimal("-5")))).hasMessageContaining("positive");
        PostingRequest one = new PostingRequest(JournalDocType.JV, LocalDate.now(), "one", Dimensions.none(), JournalSourceType.MANUAL, null, null,
                List.of(dr(bankLeaf.getId(), new BigDecimal("10"))));
        assertThatThrownBy(() -> posting.post(one)).hasMessageContaining("at least two lines");
    }

    @Test
    void rejectsGroupAccountsAndInactiveAccounts() {
        Account group = accounts.getAccountByCode("A-02-01");
        PostingRequest r = new PostingRequest(JournalDocType.JV, LocalDate.now(), "grp", Dimensions.none(), JournalSourceType.MANUAL, null, null,
                List.of(dr(group.getId(), new BigDecimal("10")), cr(bankLeaf.getId(), new BigDecimal("10"))));
        assertThatThrownBy(() -> posting.post(r)).hasMessageContaining("group account");

        // Only reachable by id: AccountResolver.usable() already refuses to hand a role
        // an inactive leaf, so a ByRole line never gets this far.
        bankLeaf.setActive(false);
        accountRepo.save(bankLeaf);
        PostingRequest closed = new PostingRequest(JournalDocType.JV, LocalDate.now(), "closed", Dimensions.none(), JournalSourceType.MANUAL, null, null,
                List.of(dr(bankLeaf.getId(), new BigDecimal("10")), cr(accounts.getAccountByCode("F-01").getId(), new BigDecimal("10"))));
        assertThatThrownBy(() -> posting.post(closed))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("inactive account");
    }

    @Test
    void unmappedRoleFailsWithRoleName() {
        PostingRequest r = new PostingRequest(JournalDocType.JV, LocalDate.now(), "x", Dimensions.ofProperty(propertyId), JournalSourceType.MANUAL, null, null,
                List.of(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("10")), cr(AccountRole.SECURITY_DEPOSIT, new BigDecimal("10"))));
        assertThatThrownBy(() -> posting.post(r)).isInstanceOf(UnmappedAccountRoleException.class).hasMessageContaining("SECURITY_DEPOSIT");
    }

    /**
     * A real cut-over batch row.
     *
     * <p>{@code journal_entries.import_batch_id} is a foreign key (changeset 88):
     * the id that exempts an entry from the period lock and that "Reverse batch"
     * finds it by has to name a batch that exists, or the entry is one nothing can
     * ever reach. A synthetic UUID used to do here and no longer does, which is the
     * constraint working.</p>
     */
    private UUID aRealBatch() {
        return batches.create(null, "cut-over").getId();
    }

    @Test
    void periodLockBlocksOrdinaryPostingsButNotOpeningBalancesOrImports() {
        fiscal.lockThrough(LocalDate.of(2026, 9, 30));
        assertThatThrownBy(() -> posting.post(contract(new BigDecimal("10")))).hasMessageContaining("locked through 2026-09-30");
        PostingRequest ob = new PostingRequest(JournalDocType.OB, LocalDate.of(2026, 9, 11), "opening", Dimensions.none(), JournalSourceType.OPENING_BALANCE, null, null,
                List.of(dr(bankLeaf.getId(), new BigDecimal("10")), cr(accounts.getAccountByCode("F-01").getId(), new BigDecimal("10"))));
        assertThat(posting.post(ob).getEntryNumber()).startsWith("OB-26/");
        PostingRequest imported = new PostingRequest(JournalDocType.TCO, LocalDate.of(2026, 9, 11), "imported", Dimensions.ofProperty(propertyId),
                JournalSourceType.IMPORT, UUID.randomUUID(), aRealBatch(),
                List.of(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("10")), cr(AccountRole.ADVANCE_RENT, new BigDecimal("10"))));
        assertThat(posting.post(imported).getImportBatchId()).isNotNull();
    }

    /**
     * The boundary of the reversal path's lock exemption
     * ({@code PostingService.reverse}: {@code docType != OB && importBatchId == null}
     * → {@code fiscal.assertOpen(date)}).
     *
     * <p>That line is the <em>only</em> period-lock guard on the reversal paths for
     * penalties, cheques, lease reversal and recognition — none of them calls
     * {@code assertOpen} for itself — so widening it would go unnoticed. These are the
     * two halves it has to keep: an ordinary entry cannot be reversed <em>into</em> a
     * locked period, and can be reversed on a date the lock leaves open. The guard is
     * on the REVERSAL's date, not the original's, which is why the same entry answers
     * both ways.</p>
     */
    @Test
    void anOrdinaryEntryStillCannotBeReversedIntoALockedPeriod() {
        JournalEntry contractEntry = posting.post(contract(new BigDecimal("61000.00")));
        PostingRequest jvRequest = new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 9, 11), "manual",
                Dimensions.none(), JournalSourceType.MANUAL, null, null,
                List.of(dr(bankLeaf.getId(), new BigDecimal("10")),
                        cr(accounts.getAccountByCode("F-01").getId(), new BigDecimal("10"))));
        JournalEntry jv = posting.post(jvRequest);

        fiscal.lockThrough(LocalDate.of(2026, 9, 30));

        assertThatThrownBy(() -> posting.reverse(contractEntry.getId(), LocalDate.of(2026, 9, 12), "wrong unit"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("locked through 2026-09-30");
        assertThatThrownBy(() -> posting.reverse(jv.getId(), LocalDate.of(2026, 9, 12), "wrong"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("locked through 2026-09-30");

        // Both are still on the books: a refused reversal writes nothing.
        assertThat(entries.findById(contractEntry.getId()).orElseThrow().getStatus()).isEqualTo(JournalStatus.POSTED);
        assertThat(entries.findById(jv.getId()).orElseThrow().getStatus()).isEqualTo(JournalStatus.POSTED);

        // …and the open period accepts exactly the same reversals.
        assertThat(posting.reverse(contractEntry.getId(), LocalDate.of(2026, 10, 1), "wrong unit").getEntryDate())
                .isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(posting.reverse(jv.getId(), LocalDate.of(2026, 10, 1), "wrong").getEntryDate())
                .isEqualTo(LocalDate.of(2026, 10, 1));
    }

    /**
     * The exempt half of the same line, on the reversal path: an {@code OB} entry and a
     * batch-carrying import entry can both be taken off inside the locked period. An
     * entry that could be posted into a closed period has to be removable from it.
     */
    @Test
    void anOpeningBalanceOrImportEntryCanBeReversedInsideTheLockedPeriod() {
        PostingRequest obRequest = new PostingRequest(JournalDocType.OB, LocalDate.of(2026, 9, 11), "opening",
                Dimensions.none(), JournalSourceType.OPENING_BALANCE, null, null,
                List.of(dr(bankLeaf.getId(), new BigDecimal("10")),
                        cr(accounts.getAccountByCode("F-01").getId(), new BigDecimal("10"))));
        PostingRequest importedRequest = new PostingRequest(JournalDocType.TCO, LocalDate.of(2026, 9, 11), "imported",
                Dimensions.ofProperty(propertyId), JournalSourceType.IMPORT, UUID.randomUUID(), aRealBatch(),
                List.of(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("10")),
                        cr(AccountRole.ADVANCE_RENT, new BigDecimal("10"))));
        JournalEntry ob = posting.post(obRequest);
        JournalEntry imported = posting.post(importedRequest);

        fiscal.lockThrough(LocalDate.of(2026, 9, 30));

        assertThat(posting.reverse(ob.getId(), LocalDate.of(2026, 9, 11), "corrected").getEntryDate())
                .isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(posting.reverse(imported.getId(), LocalDate.of(2026, 9, 11), "re-import").getEntryDate())
                .isEqualTo(LocalDate.of(2026, 9, 11));
    }

    @Test
    void reverseCreatesMirrorAndLinksBoth() {
        JournalEntry e = posting.post(contract(new BigDecimal("61000.00")));
        JournalEntry rev = posting.reverse(e.getId(), LocalDate.of(2026, 9, 12), "wrong unit");
        assertThat(rev.getDocType()).isEqualTo(JournalDocType.TCR);
        assertThat(rev.getReversalOfId()).isEqualTo(e.getId());
        assertThat(rev.getNarration()).contains("Reversal of TCO-26/1").contains("wrong unit");
        List<JournalLine> ls = lines.findByEntry_IdOrderByLineNoAsc(rev.getId());
        assertThat(ls.get(0).getAccountId()).isEqualTo(rentRecvLeaf.getId());
        assertThat(ls.get(0).getCredit()).isEqualByComparingTo("61000.00");
        assertThat(ls.get(1).getDebit()).isEqualByComparingTo("61000.00");
        JournalEntry original = entries.findById(e.getId()).orElseThrow();
        assertThat(original.getStatus()).isEqualTo(JournalStatus.REVERSED);
        assertThat(original.getReversedById()).isEqualTo(rev.getId());
    }

    /**
     * Two threads reversing the same entry at once.
     *
     * <p>{@code reverse()} read the entry with {@code findById} and then acted on
     * {@code status}/{@code reversalOfId}: both threads saw POSTED, both wrote a
     * mirror, and the original ended up pointing at whichever one committed last —
     * with the other reversal still on the books, double-counting the correction.
     * Both halves of the fix are exercised here: the PESSIMISTIC_WRITE lock in
     * {@code lockById}, and the partial unique index uq_je_reversal_of behind it.
     */
    @Test
    void concurrentReversesOfTheSameEntryProduceExactlyOneMirror() throws Exception {
        JournalEntry e = posting.post(contract(new BigDecimal("2500.00")));
        UUID entryId = e.getId();
        UUID tenant = tenantId;

        CyclicBarrier bothReady = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            racers.add(() -> {
                TenantContextHolder.setTenantId(tenant);
                try {
                    bothReady.await(10, TimeUnit.SECONDS);
                    return posting.reverse(entryId, LocalDate.of(2026, 9, 12), "race");
                } catch (RuntimeException ex) {
                    return ex;
                } finally {
                    TenantContextHolder.clear();
                }
            });
        }
        List<Future<Object>> results;
        try {
            results = pool.invokeAll(racers, 30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> f : results) outcomes.add(f.get());
        List<JournalEntry> winners = outcomes.stream().filter(JournalEntry.class::isInstance)
                .map(JournalEntry.class::cast).toList();
        List<Object> losers = outcomes.stream().filter(o -> !(o instanceof JournalEntry)).toList();

        assertThat(winners).hasSize(1);
        assertThat(losers).hasSize(1);
        // The row lock is what makes the loser a clean BusinessRuleViolationException
        // (400 "already reversed") rather than a raw uq_je_reversal_of violation: it
        // blocks until the winner commits and then re-reads the committed status.
        // Drop the lock and this assertion flips to DataIntegrityViolationException —
        // still not a duplicate, because the index is the second half of the fix.
        assertThat(losers.get(0)).isInstanceOf(BusinessRuleViolationException.class);
        assertThat(((RuntimeException) losers.get(0)).getMessage()).contains("already reversed");

        Long mirrors = jdbc.queryForObject(
                "select count(*) from journal_entries where reversal_of_id = ?", Long.class, entryId);
        assertThat(mirrors).isEqualTo(1L);

        JournalEntry original = entries.findById(entryId).orElseThrow();
        assertThat(original.getStatus()).isEqualTo(JournalStatus.REVERSED);
        assertThat(original.getReversedById()).isEqualTo(winners.get(0).getId());
    }

    @Test
    void cannotReverseTwiceOrReverseAReversal() {
        JournalEntry e = posting.post(contract(new BigDecimal("10")));
        JournalEntry rev = posting.reverse(e.getId(), LocalDate.of(2026, 9, 12), "r");
        assertThatThrownBy(() -> posting.reverse(e.getId(), LocalDate.of(2026, 9, 13), "again")).hasMessageContaining("already reversed");
        assertThatThrownBy(() -> posting.reverse(rev.getId(), LocalDate.of(2026, 9, 13), "again")).hasMessageContaining("reversal entry");
    }

    @Test
    void amountsAreNormalisedToTwoDecimals() {
        PostingRequest r = new PostingRequest(JournalDocType.JV, LocalDate.now(), "scale", Dimensions.none(), JournalSourceType.MANUAL, null, null,
                List.of(dr(bankLeaf.getId(), new BigDecimal("978.0821")), cr(accounts.getAccountByCode("F-01").getId(), new BigDecimal("978.08"))));
        JournalEntry e = posting.post(r);
        assertThat(lines.findByEntry_IdOrderByLineNoAsc(e.getId()).get(0).getDebit()).isEqualByComparingTo("978.08");
    }

    // ---- Addendum A: per-line contra account for the ledger's "Particular" column ----

    /**
     * Three Rent Receivable debits against three different credits. A ledger prints
     * one counter-account per row, so each debit has to remember which credit it was
     * raised against rather than inheriting all three.
     */
    private PostingRequest splitContract() {
        Account depositLeaf = accounts.createLeaf("Security Deposit Sample Heights", accounts.getAccountByCode("B-01-02"), propertyId);
        Account adminFeeLeaf = accounts.createLeaf("Admin Fee - Sample Heights", accounts.getAccountByCode("C-01-01"), propertyId);
        map(propertyId, AccountRole.SECURITY_DEPOSIT, depositLeaf);
        map(propertyId, AccountRole.ADMIN_FEE, adminFeeLeaf);
        return PostingRequest.ofPairs(JournalDocType.TCO, LocalDate.of(2026, 9, 11), "Contract Sample Heights SMH-324",
                Dimensions.ofProperty(propertyId), JournalSourceType.LEASE, UUID.randomUUID(), null,
                List.of(
                        pair(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("61000.00")), cr(AccountRole.ADVANCE_RENT, new BigDecimal("61000.00"))),
                        pair(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("3000.00")), cr(AccountRole.SECURITY_DEPOSIT, new BigDecimal("3000.00"))),
                        pair(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("500.00")), cr(AccountRole.ADMIN_FEE, new BigDecimal("500.00")))));
    }

    @Test
    void ofPairsGivesEveryLineItsOwnCounterAccount() {
        JournalEntry e = posting.post(splitContract());

        List<JournalLine> ls = lines.findByEntry_IdOrderByLineNoAsc(e.getId());
        assertThat(ls).hasSize(6);
        // Pairs are flattened debit-then-credit, in order.
        for (int i = 0; i < 6; i += 2) {
            JournalLine debit = ls.get(i), credit = ls.get(i + 1);
            assertThat(debit.getAccountId()).isEqualTo(rentRecvLeaf.getId());
            assertThat(debit.getContraAccountId()).isEqualTo(credit.getAccountId());
            assertThat(credit.getContraAccountId()).isEqualTo(debit.getAccountId());
        }
        assertThat(ls.stream().map(JournalLine::getContraAccountId).distinct()).hasSize(4);
    }

    @Test
    void unpairedLinesHaveNoContraAccount() {
        JournalEntry e = posting.post(contract(new BigDecimal("61000.00")));
        assertThat(lines.findByEntry_IdOrderByLineNoAsc(e.getId()))
                .extracting(JournalLine::getContraAccountId).containsOnlyNulls();
    }

    @Test
    void reversalKeepsEachLinesContraAccount() {
        JournalEntry e = posting.post(splitContract());
        List<JournalLine> original = lines.findByEntry_IdOrderByLineNoAsc(e.getId());

        JournalEntry rev = posting.reverse(e.getId(), LocalDate.of(2026, 9, 12), "wrong unit");

        List<JournalLine> reversed = lines.findByEntry_IdOrderByLineNoAsc(rev.getId());
        assertThat(reversed).hasSize(6);
        for (int i = 0; i < 6; i++) {
            assertThat(reversed.get(i).getAccountId()).isEqualTo(original.get(i).getAccountId());
            assertThat(reversed.get(i).getContraAccountId()).isEqualTo(original.get(i).getContraAccountId());
        }
    }
}
