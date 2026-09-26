package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BankAccount;
import com.datagami.rentaxis.domain.entity.Vendor;
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
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The S16-01 class outside bank reconciliation: a service that calls a {@code @Transactional}
 * bean inside its own transaction and catches that bean's refusal as an ordinary outcome.
 * The refusal leaving the bean's proxy has already marked the shared transaction
 * rollback-only, so the caller's catch runs, the caller returns, and the commit fails with
 * UnexpectedRollbackException — a 500 for a create that should have succeeded. Each case
 * here is one such "fall back when missing" path, driven outside any test transaction.
 */
@SpringBootTest
class CaughtRefusalInsideATransactionIT extends AbstractPostgresIT {

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired VendorService vendorService;
    @Autowired BankAccountService bankAccountService;
    @Autowired JdbcTemplate jdbc;

    LeaseTestFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService, accountService,
                propertyAccountService, chargeTypeService).bootstrap();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    /** VendorService.createVendor: no Vendors group (B-01-04) → a vendor without a ledger account, not a 500. */
    @Test
    void aVendorIsCreatedWithoutALedgerAccountWhenTheVendorsGroupIsMissing() {
        jdbc.update("update accounts set code = 'Z-B-01-04' where tenant_id = ? and code = 'B-01-04'", fx.tenantId());
        Vendor v = new Vendor();
        v.setNameEn("No Group Supplies LLC");
        v.setPaymentTermsDays(30);
        Vendor saved = vendorService.createVendor(v);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPayableAccount()).isNull();
    }

    /**
     * BankAccountService.createBankAccount: a property with no BANK mapping falls through
     * to its own leaf under A-02-02, and with no A-02-02 either the account is created
     * without a ledger leaf — neither is a 500.
     */
    @Test
    void aBankAccountFallsBackToItsOwnLeafWhenThePropertyHasNoBankMapping() {
        jdbc.update("delete from property_account_mappings where property_id = ? and role = 'BANK'", fx.property().getId());
        jdbc.update("delete from tenant_default_account_mappings where tenant_id = ? and role = 'BANK'", fx.tenantId());
        BankAccount b = new BankAccount();
        b.setBankName("Mashreq");
        b.setAccountNumber("019000001234");
        b.setProperty(fx.property());
        BankAccount saved = bankAccountService.createBankAccount(b);
        assertThat(saved.getCoaAccount()).isNotNull();
        assertThat(saved.getCoaAccount().getName()).isEqualTo("Mashreq - 1234");

        jdbc.update("update accounts set code = 'Z-A-02-02' where tenant_id = ? and code = 'A-02-02'", fx.tenantId());
        BankAccount c = new BankAccount();
        c.setBankName("ADCB");
        c.setAccountNumber("11112222");
        c.setProperty(fx.property());
        BankAccount second = bankAccountService.createBankAccount(c);
        assertThat(second.getId()).isNotNull();
        assertThat(second.getCoaAccount()).isNull();
    }
}
