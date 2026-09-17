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
import java.util.List;
import java.util.UUID;

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

    UUID tenantId; UUID propertyId;
    Account rentRecvLeaf, advanceRentLeaf, bankLeaf;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg(); org.setName("Post-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        propertyAccounts.seedDefaultTemplateAndDefaults();
        Property p = new Property(); p.setNameEn("L'Olivier"); p.setEmirate(Emirate.DUBAI);
        propertyId = propertyRepo.save(p).getId();
        rentRecvLeaf = accounts.createLeaf("Rent Receivable - L'Olivier", accounts.getAccountByCode("A-02-01"), propertyId);
        advanceRentLeaf = accounts.createLeaf("Advance Rent - L'Olivier", accounts.getAccountByCode("B-01-01"), propertyId);
        bankLeaf = accounts.createLeaf("Emirates Islamic - L'Olivier", accounts.getAccountByCode("A-02-02"), propertyId);
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
        return new PostingRequest(JournalDocType.TCO, LocalDate.of(2026, 9, 11), "Contract L'Olivier OLV-324",
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

    @Test
    void periodLockBlocksOrdinaryPostingsButNotOpeningBalancesOrImports() {
        fiscal.lockThrough(LocalDate.of(2026, 9, 30));
        assertThatThrownBy(() -> posting.post(contract(new BigDecimal("10")))).hasMessageContaining("locked through 2026-09-30");
        PostingRequest ob = new PostingRequest(JournalDocType.OB, LocalDate.of(2026, 9, 11), "opening", Dimensions.none(), JournalSourceType.OPENING_BALANCE, null, null,
                List.of(dr(bankLeaf.getId(), new BigDecimal("10")), cr(accounts.getAccountByCode("F-01").getId(), new BigDecimal("10"))));
        assertThat(posting.post(ob).getEntryNumber()).startsWith("OB-26/");
        PostingRequest imported = new PostingRequest(JournalDocType.TCO, LocalDate.of(2026, 9, 11), "imported", Dimensions.ofProperty(propertyId),
                JournalSourceType.IMPORT, UUID.randomUUID(), UUID.randomUUID(),
                List.of(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("10")), cr(AccountRole.ADVANCE_RENT, new BigDecimal("10"))));
        assertThat(posting.post(imported).getImportBatchId()).isNotNull();
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
        Account depositLeaf = accounts.createLeaf("Security Deposit L'Olivier", accounts.getAccountByCode("B-01-02"), propertyId);
        Account adminFeeLeaf = accounts.createLeaf("Admin Fee - L'Olivier", accounts.getAccountByCode("C-01-01"), propertyId);
        map(propertyId, AccountRole.SECURITY_DEPOSIT, depositLeaf);
        map(propertyId, AccountRole.ADMIN_FEE, adminFeeLeaf);
        return PostingRequest.ofPairs(JournalDocType.TCO, LocalDate.of(2026, 9, 11), "Contract L'Olivier OLV-324",
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
