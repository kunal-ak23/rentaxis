package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.cheque.DepositBatchRequest;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.BankAccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BankAccount;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.ChangesetSql;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #356 R2 ruling: every property has its own BANK leaf and every BANK leaf is
 * owned by a bank account — in either onboarding order, and with several bank
 * accounts none of which was flagged default. Each case posts a lease and clears a
 * cheque into the property's own, owned leaf.
 */
@SpringBootTest
class BankLeafOnboardingIT extends AbstractPostgresIT {

    static final LocalDate CONTRACT = LocalDate.of(2026, 1, 1);
    static final LocalDate START = LocalDate.of(2026, 1, 1);
    static final LocalDate END = LocalDate.of(2026, 12, 31);

    @Autowired BankAccountService bankAccounts;
    @Autowired ChequeService cheques;
    @Autowired LeasePostingService leasePosting;
    @Autowired ChequeGenerationService generation;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    LeaseTestFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService, accountService,
                propertyAccountService, chargeTypeService).bootstrap().withLeaseServices(leaseService, generation, leasePosting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private BankAccount bank(String name, String number) {
        BankAccount b = new BankAccount();
        b.setBankName(name);
        b.setAccountNumber(number);
        return bankAccounts.createBankAccount(b);
    }

    /** The property's own BANK leaf: its mapping, scoped to it. */
    private UUID ownBankLeaf(Property p) {
        return jdbc.queryForObject("""
                select m.account_id from property_account_mappings m join accounts a on a.id = m.account_id
                where m.property_id = ? and m.role = 'BANK' and a.property_id = ?""", UUID.class, p.getId(), p.getId());
    }

    private UUID ownerOf(UUID leaf) {
        List<UUID> r = jdbc.queryForList("select bank_account_id from bank_account_ledgers where account_id = ?",
                UUID.class, leaf);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Posts a one-cheque lease on {@code p}, deposits and clears it; returns the leaf the CRT debited. */
    private UUID postAndClear(Property p, String number) {
        Unit u = fx.createUnit(p, "U-" + number);
        PostLeaseResponse r = fx.postedLease(u, fx.createRenter("Renter " + number), CONTRACT, START, END,
                List.of(line("RENT", "36000")), 1, number);
        ChequeDTO c = r.cheques().stream().filter(x -> x.mode() == ChequeMode.PDC).findFirst().orElseThrow();
        cheques.depositBatch(new DepositBatchRequest(List.of(c.id()), START, null, null));
        return cheques.clear(c.id(), ChequeActionRequest.on(START.plusDays(2))).debitAccountId();
    }

    @Test
    void bankAccountFirstThenProperty() {
        BankAccount ei = bank("Emirates Islamic", "0260000000123");
        assertThat(ei.isDefault()).as("the first bank account is the default").isTrue();
        Property p = fx.createProperty("BF");
        UUID leaf = ownBankLeaf(p);
        assertThat(ownerOf(leaf)).isEqualTo(ei.getId());
        assertThat(postAndClear(p, "310001")).isEqualTo(leaf);
    }

    @Test
    void propertyFirstThenBankAccount() {
        Property p = fx.createProperty("PF");
        UUID leaf = ownBankLeaf(p);
        assertThat(ownerOf(leaf)).as("no bank account yet").isNull();
        BankAccount ei = bank("Emirates Islamic", "0260000000456");
        assertThat(ownerOf(leaf)).as("the new bank account takes the property's leaf").isEqualTo(ei.getId());
        assertThat(ownerOf(ownBankLeaf(fx.property()))).isEqualTo(ei.getId());
        assertThat(postAndClear(p, "320001")).isEqualTo(leaf);
    }

    @Test
    void twoBankAccountsNoneFlaggedDefaultThenProperty() {
        BankAccount first = bank("Emirates Islamic", "0260000000789");
        BankAccount second = bank("ENBD", "1010000000001");
        assertThat(jdbc.queryForObject("select is_default from bank_accounts where id = ?", Boolean.class, first.getId())).isTrue();
        assertThat(jdbc.queryForObject("select is_default from bank_accounts where id = ?", Boolean.class, second.getId())).isFalse();
        Property p = fx.createProperty("TWO");
        UUID leaf = ownBankLeaf(p);
        assertThat(ownerOf(leaf)).as("attached to the default (first) bank account").isEqualTo(first.getId());
        assertThat(postAndClear(p, "330001")).isEqualTo(leaf);
    }

    /** Changeset 126 on data it did not see at start-up: attaches, and a second run changes nothing. */
    @Test
    void theBackfillAttachesUnownedPropertyLeavesAndIsIdempotent() {
        UUID tenant = fx.tenantId();
        UUID bankId = tx.execute(st -> {
            BankAccount b = new BankAccount();
            b.setBankName("Legacy Bank");
            b.setAccountNumber("99990000");
            b.setTenantId(tenant);
            b.setActive(true);
            return bankAccountRepo.save(b).getId();   // written without the service: no default, no attach
        });
        UUID leaf = ownBankLeaf(fx.property());
        assertThat(ownerOf(leaf)).isNull();
        for (int run = 0; run < 2; run++) {
            tx.executeWithoutResult(st -> ChangesetSql.of("126-attach-property-bank-leaves.yaml").forEach(jdbc::execute));
        }
        assertThat(ownerOf(leaf)).isEqualTo(bankId);
        assertThat(jdbc.queryForObject("select is_default from bank_accounts where id = ?", Boolean.class, bankId)).isTrue();
    }

    @Autowired BankAccountRepository bankAccountRepo;
}
