package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Dimensions;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F15-11: every journal balances per property. A journal whose lines span
 * properties is refused unless it carries its clearing legs; the repair gives a
 * past unbalanced journal one clearing journal, once.
 */
@SpringBootTest
class InterPropertyClearingIT extends AbstractPostgresIT {

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired PropertyService properties;
    @Autowired PostingService posting;
    @Autowired InterPropertyRepairService repair;
    @Autowired LedgerQueryService ledger;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired JdbcTemplate jdbc;

    UUID tenantId;
    Property p1, p2;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("IPC-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        propertyAccounts.seedDefaultTemplateAndDefaults();
        p1 = property("North Tower");
        p2 = property("South Tower");
    }

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    private Property property(String name) {
        Property p = new Property();
        p.setNameEn(name);
        p.setEmirate(Emirate.DUBAI);
        return properties.createProperty(p);
    }

    /** Dr receivable on P1 / Cr receivable on P2: the transfer carry's shape. */
    private PostingRequest crossProperty(LocalDate date) {
        BigDecimal a = new BigDecimal("13150.00");
        return new PostingRequest(JournalDocType.JV, date, "carry", Dimensions.ofProperty(p2.getId()),
                JournalSourceType.LEASE, null, null, List.of(
                PostingRequest.dr(AccountRole.RENT_RECEIVABLE, a).withDims(Dimensions.ofProperty(p1.getId())),
                PostingRequest.cr(AccountRole.RENT_RECEIVABLE, a).withDims(Dimensions.ofProperty(p2.getId()))));
    }

    private void assertEveryPropertyBalances() {
        for (Property p : List.of(p1, p2)) {
            List<TrialBalanceRowDTO> rows = ledger.trialBalance(LocalDate.of(2030, 1, 1), p.getId());
            BigDecimal d = rows.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal c = rows.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(d).as("TB of " + p.getNameEn()).isEqualByComparingTo(c);
        }
        assertThat(clearingNet()).as("clearing nets to zero").isZero();
    }

    private BigDecimal clearingNet() {
        return jdbc.queryForObject("""
                select coalesce(sum(l.debit - l.credit), 0) from journal_lines l join accounts a on a.id = l.account_id
                where l.tenant_id = ? and (a.code like 'A-02-06%' or a.report_line = 'INTERPROPERTY_CLEARING')""", BigDecimal.class, tenantId);
    }

    /** Rewrites a posted line as the pre-F15-11 data looks (lines are immutable, so the trigger is bypassed for it). */
    private void legacy(String update) {
        jdbc.execute("set session_replication_role = replica; " + update + "; set session_replication_role = default");
    }

    @Test
    void aJournalSpanningPropertiesIsRefusedWithoutItsClearingLegs() {
        assertThatThrownBy(() -> posting.post(crossProperty(LocalDate.of(2026, 9, 1))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("does not balance per property");
        JournalEntry e = posting.post(crossProperty(LocalDate.of(2026, 9, 1)).withInterPropertyClearing());
        assertThat(e.getLines()).hasSize(4);
        // Each property's clearing leg is its own leaf.
        long leaves = e.getLines().stream().filter(l -> l.getAccount().getCode().startsWith("A-02-06") || "INTERPROPERTY_CLEARING".equals(l.getAccount().getReportLine()))
                .map(l -> l.getAccount().getId()).distinct().count();
        assertThat(leaves).isEqualTo(2);
        assertEveryPropertyBalances();
    }

    @Test
    void theRepairClearsAPastJournalOnceAndASecondRunPostsNothing() {
        // A journal from before the rule: posted per-property balanced, then its credit moved to P2.
        BigDecimal a = new BigDecimal("3000.00");
        JournalEntry legacy = posting.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 9, 2), "legacy",
                Dimensions.ofProperty(p1.getId()), JournalSourceType.LEASE, null, null, List.of(
                PostingRequest.dr(AccountRole.SECURITY_DEPOSIT, a), PostingRequest.cr(AccountRole.SECURITY_DEPOSIT, a))));
        legacy("update journal_lines set property_id = '" + p2.getId() + "' where journal_entry_id = '" + legacy.getId() + "' and credit > 0");

        assertThat(repair.unbalanced()).containsExactly(legacy.getId());
        InterPropertyRepairService.Result first = repair.repair();
        assertThat(first.repaired()).hasSize(1);
        assertThat(first.repaired().getFirst().repairDate()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(first.repaired().getFirst().originalId()).isEqualTo(legacy.getId());
        assertEveryPropertyBalances();

        assertThat(repair.repair().repaired()).isEmpty();
        assertThat(repair.unbalanced()).isEmpty();
    }

    @Test
    void aRepairInsideTheLockIsDatedTheFirstOpenDay() {
        BigDecimal a = new BigDecimal("500.00");
        JournalEntry legacy = posting.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 1, 10), "legacy",
                Dimensions.ofProperty(p1.getId()), JournalSourceType.LEASE, null, null, List.of(
                PostingRequest.dr(AccountRole.CASH, a), PostingRequest.cr(AccountRole.OTHER_INCOME, a))));
        // The cash line moves off every property: P1 is left with the credit alone.
        legacy("update journal_lines set property_id = null where journal_entry_id = '" + legacy.getId() + "' and debit > 0");
        fiscal.lockThrough(LocalDate.of(2026, 3, 31));
        InterPropertyRepairService.Result r = repair.repair();
        assertThat(r.repaired()).hasSize(1);
        assertThat(r.repaired().getFirst().repairDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertEveryPropertyBalances();
    }
}
