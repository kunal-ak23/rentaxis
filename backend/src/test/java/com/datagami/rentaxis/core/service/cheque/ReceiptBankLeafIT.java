package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.bank.BankAccountLedgerService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BankAccount;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.BankAccountRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F14-16: every receipt lands in a bank leaf some bank account owns. Property
 * creation maps the new property to an owned leaf instead of generating an orphan
 * one, and a row stamped with an orphan leaf is received into the owned leaf.
 */
@SpringBootTest
class ReceiptBankLeafIT extends AbstractPostgresIT {

    @Autowired ChequeService cheques;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeasePostingService posting;
    @Autowired LeaseService leaseService;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired BankAccountRepository bankAccounts;
    @Autowired BankAccountLedgerService ledgers;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired TransactionTemplate tx;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);

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

    private UUID bankLeafOf(Property p) {
        return tx.execute(s -> resolver.resolve(AccountRole.BANK, p.getId()).getId());
    }

    /** A tenant-wide bank leaf, beside the property ones. */
    private UUID tenantBankLeaf() {
        return tx.execute(s -> {
            var parent = resolver.resolve(AccountRole.BANK, fixtures.property().getId()).getParent();
            return accountService.createLeaf("Emirates Islamic - Main " + UUID.randomUUID().toString().substring(0, 4),
                    parent, null).getId();
        });
    }

    /** A bank account owning {@code leaf}, the tenant's only one. */
    private void bankAccountOwning(UUID leaf) {
        UUID id = tx.execute(s -> {
            BankAccount b = new BankAccount();
            b.setBankName("Emirates Islamic");
            b.setAccountNumber("0012-" + UUID.randomUUID().toString().substring(0, 6));
            b.setTenantId(fixtures.tenantId());
            return bankAccounts.save(b).getId();
        });
        ledgers.setLeaves(id, List.of(leaf));
    }

    @Test
    void aNewPropertyIsMappedToTheOwnedLeafInsteadOfAnOrphan() {
        UUID owned = tenantBankLeaf();
        bankAccountOwning(owned);
        long leavesBefore = accountRepo.count();

        Property third = fixtures.createProperty("NEW");

        assertThat(bankLeafOf(third)).as("receipts for the new property land in the owned leaf").isEqualTo(owned);
        assertThat(accountRepo.findAll()).as("no orphan bank leaf was generated")
                .noneMatch(a -> a.getName() != null && a.getName().contains(third.getNameEn())
                        && a.getName().startsWith("Emirates Islamic"));
        assertThat(accountRepo.count()).isGreaterThan(leavesBefore); // the other template leaves still exist
    }

    @Test
    void aRowStampedWithAnOrphanLeafIsClearedIntoTheOwnedLeaf() {
        UUID orphan = bankLeafOf(fixtures.property());
        PostLeaseResponse r = fixtures.postedLease(CONTRACT_DATE, START, END, List.of(line("RENT", "36000")), 2, null);
        ChequeDTO row = r.cheques().stream().filter(c -> c.mode() == ChequeMode.PDC).findFirst().orElseThrow();
        assertThat(row.debitAccountId()).as("generated before any bank account existed").isEqualTo(orphan);

        UUID owned = tenantBankLeaf();
        bankAccountOwning(owned);

        cheques.deposit(row.id(), ChequeActionRequest.on(row.chequeDate()));
        ChequeDTO cleared = cheques.clear(row.id(), ChequeActionRequest.on(row.chequeDate()));

        assertThat(cleared.debitAccountId()).isEqualTo(owned);
    }

    private UUID cashLeaf() {
        return tx.execute(s -> resolver.resolve(AccountRole.CASH, fixtures.property().getId()).getId());
    }

    /** R1 P2-2: a CASH row the grid generates defaults to cash in hand, not the bank. */
    @Test
    void aGeneratedCashRowDefaultsToCashInHand() {
        UUID lease = fixtures.draftLease(CONTRACT_DATE, START, END, List.of(line("RENT", "36000")));
        List<ChequeDTO> rows = chequeGeneration.saveRows(lease, List.of(
                new com.datagami.rentaxis.api.dto.lease.ChequeRowInput(null, null, CONTRACT_DATE, null, START,
                        null, null, null, new java.math.BigDecimal("18000"), "Rent", ChequeMode.CASH),
                new com.datagami.rentaxis.api.dto.lease.ChequeRowInput(null, null, CONTRACT_DATE, "900001",
                        START.plusMonths(6), "Emirates NBD", null, null, new java.math.BigDecimal("18000"), "Rent",
                        ChequeMode.PDC)));
        assertThat(rows).filteredOn(r -> r.mode() == ChequeMode.CASH).extracting(ChequeDTO::debitAccountId)
                .containsExactly(cashLeaf());
        assertThat(rows).filteredOn(r -> r.mode() == ChequeMode.PDC).extracting(ChequeDTO::debitAccountId)
                .containsExactly(bankLeafOf(fixtures.property()));
    }

    /**
     * R1 P2-2/P2-3: what the Receive dialog shows is what posts. A CASH row stamped
     * with a bank leaf (the old grid default) resolves to cash in hand, the settlement
     * target says so, and a receive with no account lands there.
     */
    @Test
    void theSettlementTargetIsWhereTheReceiptPosts() {
        UUID orphan = bankLeafOf(fixtures.property());
        PostLeaseResponse r = fixtures.postedLease(CONTRACT_DATE, START, END, List.of(line("RENT", "36000")), 2, null);
        ChequeDTO cash = cheques.addRowToPostedLease(r.lease().getId(),
                new com.datagami.rentaxis.api.dto.lease.ChequeRowInput(null, null, START, null, START, null, null,
                        orphan, new java.math.BigDecimal("1500"), "Cash", ChequeMode.CASH));
        assertThat(cash.debitAccountId()).isEqualTo(orphan);
        UUID owned = tenantBankLeaf();
        bankAccountOwning(owned);

        var target = cheques.settlementTarget(cash.id());
        assertThat(target.target().id()).isEqualTo(cashLeaf());
        assertThat(target.options()).extracting(o -> o.id()).contains(cashLeaf(), owned).doesNotContain(orphan);
        assertThat(cheques.receive(cash.id(), ChequeActionRequest.on(START)).debitAccountId()).isEqualTo(cashLeaf());

        ChequeDTO pdc = r.cheques().stream().filter(c -> c.mode() == ChequeMode.PDC).findFirst().orElseThrow();
        assertThat(cheques.settlementTarget(pdc.id()).target().id()).as("an orphan bank leaf resolves to the owned one")
                .isEqualTo(owned);
    }

    /** R2 N-4: a bank leaf named by the caller must be one a bank account owns; cash leaves pass. */
    @Test
    void anUnownedBankLeafNamedByTheCallerIsRefused() {
        UUID orphan = bankLeafOf(fixtures.property());
        PostLeaseResponse r = fixtures.postedLease(CONTRACT_DATE, START, END, List.of(line("RENT", "36000")), 2, null);
        ChequeDTO transfer = cheques.addRowToPostedLease(r.lease().getId(),
                new com.datagami.rentaxis.api.dto.lease.ChequeRowInput(null, null, START, null, START, null, null,
                        null, new java.math.BigDecimal("1500"), "Transfer", ChequeMode.TRANSFER));
        UUID owned = tenantBankLeaf();
        bankAccountOwning(owned);

        assertThatThrownBy(() -> cheques.receive(transfer.id(), new ChequeActionRequest(START, null, null, orphan)))
                .isInstanceOf(com.datagami.rentaxis.api.exception.BusinessRuleViolationException.class)
                .hasMessageContaining("is not attached to a bank account");
        assertThat(cheques.receive(transfer.id(), new ChequeActionRequest(START, null, null, owned)).debitAccountId())
                .isEqualTo(owned);

        ChequeDTO cash = cheques.addRowToPostedLease(r.lease().getId(),
                new com.datagami.rentaxis.api.dto.lease.ChequeRowInput(null, null, START, null, START, null, null,
                        null, new java.math.BigDecimal("500"), "Cash", ChequeMode.CASH));
        assertThat(cheques.receive(cash.id(), new ChequeActionRequest(START, null, null, cashLeaf())).debitAccountId())
                .isEqualTo(cashLeaf());
    }
}
