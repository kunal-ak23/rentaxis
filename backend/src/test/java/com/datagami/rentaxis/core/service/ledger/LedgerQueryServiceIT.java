package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.core.service.ledger.PostingRequest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LedgerQueryServiceIT extends AbstractPostgresIT {

    @Autowired LedgerQueryService ledger;
    @Autowired PostingService posting;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired PropertyService properties;
    @Autowired AccountResolver resolver;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired JournalEntryRepository entries;

    UUID propertyId, renterId, leaseId, tcoId;

    @BeforeEach void setUp() {
        LandlordOrg org = new LandlordOrg(); org.setName("LQ-" + UUID.randomUUID());
        TenantContextHolder.setTenantId(orgRepo.save(org).getId());
        accounts.seedDefaultAccounts(); propertyAccounts.seedDefaultTemplateAndDefaults();
        Property p = new Property(); p.setNameEn("L'Olivier"); p.setEmirate(Emirate.DUBAI);
        Property property = properties.createProperty(p);
        propertyId = property.getId();
        Renter r = new Renter(); r.setNameEn("Prabhjot Singh");
        Renter renter = renterRepo.save(r);
        renterId = renter.getId();
        // journal_entries.lease_id and .renter_id are real foreign keys (changeset 81),
        // so the dimensions have to point at rows that exist.
        Unit u = new Unit(); u.setProperty(property); u.setUnitNumber("304");
        Lease lease = new Lease();
        lease.setUnit(unitRepo.save(u)); lease.setRenter(renter);
        lease.setStartDate(LocalDate.of(2026, 9, 11)); lease.setEndDate(LocalDate.of(2027, 9, 10));
        lease.setRentAmount(new BigDecimal("61000")); lease.setDepositAmount(new BigDecimal("3000"));
        leaseId = leaseRepo.save(lease).getId();
        Dimensions dims = new Dimensions(propertyId, null, leaseId, renterId, null);
        // TCO 11-09-2026, posted as pairs: the receivable faces advance rent, deposit and
        // admin fee on three separate rows (Addendum A), not all three on every row.
        tcoId = posting.post(PostingRequest.ofPairs(JournalDocType.TCO, LocalDate.of(2026, 9, 11), "Contract", dims,
                JournalSourceType.LEASE, leaseId, null, List.of(
                        pair(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("61000")), cr(AccountRole.ADVANCE_RENT, new BigDecimal("61000"))),
                        pair(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("3000")), cr(AccountRole.SECURITY_DEPOSIT, new BigDecimal("3000"))),
                        pair(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("500")), cr(AccountRole.ADMIN_FEE, new BigDecimal("500")))))).getId();
        // PDR 11-09-2026: Dr PDC 13,700 / Cr RR 13,700
        posting.post(new PostingRequest(JournalDocType.PDR, LocalDate.of(2026, 9, 11), "PDC 1", dims, JournalSourceType.CHEQUE, UUID.randomUUID(), null, List.of(
                dr(AccountRole.PDC_RECEIVABLE, new BigDecimal("13700")), cr(AccountRole.RENT_RECEIVABLE, new BigDecimal("13700")))));
        // CRT 15-09-2026: Dr Bank / Cr PDC 13,700
        posting.post(new PostingRequest(JournalDocType.CRT, LocalDate.of(2026, 9, 15), "Cleared", dims, JournalSourceType.CHEQUE, UUID.randomUUID(), null, List.of(
                dr(AccountRole.BANK, new BigDecimal("13700")), cr(AccountRole.PDC_RECEIVABLE, new BigDecimal("13700")))));
    }
    @AfterEach void clear() { TenantContextHolder.clear(); }

    @Test
    void accountLedgerHasRunningBalanceAndCounterAccountParticular() {
        Account rr = resolver.resolve(AccountRole.RENT_RECEIVABLE, propertyId);
        AccountLedgerDTO l = ledger.accountLedger(rr.getId(), new LedgerQueryService.LedgerFilter(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, null, null, null));
        assertThat(l.openingBalance()).isEqualByComparingTo("0");
        assertThat(l.rows()).hasSize(4);
        // Three receivable rows from the TCO, each naming the one account it faces.
        assertThat(l.rows().get(0).debit()).isEqualByComparingTo("61000");
        assertThat(l.rows().get(0).particular()).isEqualTo("Advance Rent - L'Olivier");
        assertThat(l.rows().get(0).balance()).isEqualByComparingTo("61000");
        assertThat(l.rows().get(1).debit()).isEqualByComparingTo("3000");
        assertThat(l.rows().get(1).particular()).isEqualTo("Security Deposit L'Olivier");
        assertThat(l.rows().get(1).balance()).isEqualByComparingTo("64000");
        assertThat(l.rows().get(2).debit()).isEqualByComparingTo("500");
        assertThat(l.rows().get(2).particular()).isEqualTo("Admin Fee - L'Olivier");
        assertThat(l.rows().get(2).balance()).isEqualByComparingTo("64500");
        // The PDR is unpaired, so its Particular falls back to the entry's other accounts.
        assertThat(l.rows().get(3).credit()).isEqualByComparingTo("13700");
        assertThat(l.rows().get(3).particular()).isEqualTo("PDC Receivable L'Olivier");
        assertThat(l.rows().get(3).balance()).isEqualByComparingTo("50800");
        assertThat(l.closingBalance()).isEqualByComparingTo("50800");
        assertThat(l.totalDebit()).isEqualByComparingTo("64500");
        assertThat(l.totalCredit()).isEqualByComparingTo("13700");
        assertThat(l.truncated()).isFalse();
    }

    @Test
    void openingBalanceCarriesActivityBeforeTheRange() {
        Account pdc = resolver.resolve(AccountRole.PDC_RECEIVABLE, propertyId);
        AccountLedgerDTO l = ledger.accountLedger(pdc.getId(), new LedgerQueryService.LedgerFilter(LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 30), null, null, null, null));
        assertThat(l.openingBalance()).isEqualByComparingTo("13700");
        assertThat(l.rows()).hasSize(1);
        assertThat(l.closingBalance()).isEqualByComparingTo("0");

        // The first day of the range belongs to the rows, not to the opening balance:
        // counting it in both would show the day's movement twice.
        AccountLedgerDTO whole = ledger.accountLedger(pdc.getId(), new LedgerQueryService.LedgerFilter(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 30), null, null, null, null));
        assertThat(whole.openingBalance()).isEqualByComparingTo("0");
        assertThat(whole.rows()).hasSize(2);
        assertThat(whole.closingBalance()).isEqualByComparingTo("0");
    }

    /**
     * The ledger queries are native SQL, which the Hibernate tenant filter does not
     * touch, so tenant_id is a parameter on every one of them. Dropping it would hand
     * one landlord another's ledger — a P0.
     */
    @Test
    void nativeLedgerQueriesAreScopedToTheCallersTenant() {
        Account rr = resolver.resolve(AccountRole.RENT_RECEIVABLE, propertyId);
        LedgerQueryService.LedgerFilter september = new LedgerQueryService.LedgerFilter(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, null, null, null);

        LandlordOrg other = new LandlordOrg(); other.setName("LQ-other-" + UUID.randomUUID());
        TenantContextHolder.setTenantId(orgRepo.save(other).getId());

        assertThat(ledger.trialBalance(LocalDate.of(2026, 9, 30), null)).isEmpty();
        assertThat(ledger.generalLedger(List.of(), september)).isEmpty();
        assertThat(ledger.renterLedger(renterId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))).isEmpty();
        assertThatThrownBy(() -> ledger.accountLedger(rr.getId(), september)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void renterLedgerGroupsByAccountAndOnlyShowsThatRenter() {
        List<AccountLedgerDTO> l = ledger.renterLedger(renterId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(l).extracting(AccountLedgerDTO::accountName).containsExactlyInAnyOrder(
                "Rent Receivable - L'Olivier", "Advance Rent - L'Olivier", "Security Deposit L'Olivier", "Admin Fee - L'Olivier",
                "PDC Receivable L'Olivier", "Emirates Islamic - L'Olivier");
        assertThat(ledger.renterLedger(UUID.randomUUID(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))).isEmpty();
    }

    @Test
    void generalLedgerWithNoAccountIdsReturnsEveryLeafWithActivityInRange() {
        List<AccountLedgerDTO> gl = ledger.generalLedger(List.of(), new LedgerQueryService.LedgerFilter(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 15), null, null, null, null));
        assertThat(gl).extracting(AccountLedgerDTO::accountName).containsExactlyInAnyOrder("Emirates Islamic - L'Olivier", "PDC Receivable L'Olivier");
    }

    /**
     * A general ledger with no range asked for is not "every account since 2000": it is
     * the current month to date. An account whose only movement was last month has to
     * stay out of that answer.
     */
    @Test
    void generalLedgerWithNoRangeDefaultsToTheCurrentMonth() {
        Dimensions dims = new Dimensions(propertyId, null, leaseId, renterId, null);
        LocalDate today = LocalDate.now();
        posting.post(new PostingRequest(JournalDocType.JV, today.minusMonths(1), "last month", dims, JournalSourceType.MANUAL, null, null, List.of(
                dr(AccountRole.MAINTENANCE_CHARGES, new BigDecimal("200")), cr(AccountRole.CASH, new BigDecimal("200")))));
        posting.post(new PostingRequest(JournalDocType.JV, today, "this month", dims, JournalSourceType.MANUAL, null, null, List.of(
                dr(AccountRole.RENT_PENALTY, new BigDecimal("300")), cr(AccountRole.CASH, new BigDecimal("300")))));

        List<AccountLedgerDTO> gl = ledger.generalLedger(List.of(), new LedgerQueryService.LedgerFilter(null, null, null, null, null, null));

        assertThat(gl).extracting(AccountLedgerDTO::accountName)
                .contains("Rent Penalty - L'Olivier")
                .doesNotContain("Maintenance Charges - L'Olivier");
        AccountLedgerDTO penalty = gl.stream().filter(a -> a.accountName().equals("Rent Penalty - L'Olivier")).findFirst().orElseThrow();
        assertThat(penalty.rows()).hasSize(1);
        assertThat(penalty.closingBalance()).isEqualByComparingTo("300");
    }

    @Test
    void trialBalanceBalancesAndFiltersByProperty() {
        List<TrialBalanceRowDTO> tb = ledger.trialBalance(LocalDate.of(2026, 9, 30), null);
        BigDecimal dr = tb.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cr = tb.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(dr).isEqualByComparingTo(cr);
        TrialBalanceRowDTO adv = tb.stream().filter(r -> r.name().equals("Advance Rent - L'Olivier")).findFirst().orElseThrow();
        assertThat(adv.balance()).isEqualByComparingTo("-61000"); // credit balance, signed debit-positive
        assertThat(ledger.trialBalance(LocalDate.of(2026, 9, 10), null)).isEmpty();
        assertThat(ledger.trialBalance(LocalDate.of(2026, 9, 30), UUID.randomUUID())).isEmpty();
    }

    /**
     * Task 3's {@code search} needs HQL casts around every nullable parameter: without
     * them Postgres cannot infer a type for the bare {@code is null} test and rejects
     * the statement the moment a caller passes a non-null filter. That fix had no
     * permanent test, so both shapes are exercised here — a filtered search, and the
     * all-null one that has to keep matching everything.
     */
    @Test
    void journalEntrySearchAcceptsBothFilteredAndAllNullParameters() {
        assertThat(entries.search(JournalDocType.TCO, null, null, propertyId, null, null, PageRequest.of(0, 10)))
                .extracting(JournalEntry::getId).containsExactly(tcoId);
        assertThat(entries.search(null, null, null, propertyId, null, null, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(3);
        assertThat(entries.search(JournalDocType.PDR, null, null, propertyId, null, null, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        assertThat(entries.search(JournalDocType.TCO, LocalDate.of(2026, 9, 12), null, propertyId, null, null, PageRequest.of(0, 10)).getTotalElements()).isZero();
        assertThat(entries.search(JournalDocType.TCO, null, null, propertyId, UUID.randomUUID(), null, PageRequest.of(0, 10)).getTotalElements()).isZero();
        // The cut-over drill-through (plan 4): the same cast rule applies to it, and
        // a batch id nothing carries must match nothing rather than everything.
        assertThat(entries.search(null, null, null, null, null, UUID.randomUUID(), PageRequest.of(0, 10)).getTotalElements()).isZero();
        // All-null filters match everything the caller can see, which is at least this test's three entries.
        assertThat(entries.search(null, null, null, null, null, null, PageRequest.of(0, 1)).getTotalElements()).isGreaterThanOrEqualTo(3);
    }
}
