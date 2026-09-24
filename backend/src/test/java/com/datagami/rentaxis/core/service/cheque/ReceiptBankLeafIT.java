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
        Property second = fixtures.createProperty("OWN");
        UUID owned = bankLeafOf(second);
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

        UUID owned = bankLeafOf(fixtures.createProperty("OWN"));
        bankAccountOwning(owned);

        cheques.deposit(row.id(), ChequeActionRequest.on(row.chequeDate()));
        ChequeDTO cleared = cheques.clear(row.id(), ChequeActionRequest.on(row.chequeDate()));

        assertThat(cleared.debitAccountId()).isEqualTo(owned);
    }
}
