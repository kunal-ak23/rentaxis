package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.FinancialTransaction;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.FinancialTransactionRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code GET /api/v1/finance/transactions} filters combine instead of
 * being applied in exclusive priority order.
 *
 * <p>The original implementation dispatched to one derived query per
 * combination: accountType was silently ignored whenever propertyId/unitId was
 * set, and a single-sided date range was ignored entirely — while the web and
 * mobile filter UIs let users set exactly those combinations and presented the
 * unfiltered result as filtered. The service now builds one Specification with
 * every supplied predicate. Runs against real Postgres because split-children
 * exclusion and tenant filtering are SQL-level behavior. Requires Docker.</p>
 */
@SpringBootTest
@Testcontainers
class FinancialTransactionServiceFilterIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired FinancialTransactionService service;
    @Autowired FinancialTransactionRepository transactionRepository;
    @Autowired LandlordOrgRepository landlordOrgRepository;
    @Autowired PropertyRepository propertyRepository;
    @Autowired UnitRepository unitRepository;
    @Autowired AccountRepository accountRepository;

    private Property propertyA;
    private Property propertyB;
    private Unit unitA;
    private Account incomeAccount;
    private Account expenseAccount;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("TxnFilter-" + UUID.randomUUID());
        UUID tenantId = landlordOrgRepository.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);

        incomeAccount = seedAccount("C-01-01", "Rental Income", AccountType.INCOME);
        expenseAccount = seedAccount("D-01-01", "Maintenance", AccountType.EXPENSE);

        propertyA = seedProperty("Filter-Property-A");
        propertyB = seedProperty("Filter-Property-B");

        unitA = new Unit();
        unitA.setProperty(propertyA);
        unitA.setUnitNumber("U-1");
        unitA = unitRepository.save(unitA);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void accountTypeFilter_combinesWithPropertyFilter() {
        txn("a-expense", expenseAccount, LocalDate.of(2026, 1, 10), propertyA, null);
        txn("a-income", incomeAccount, LocalDate.of(2026, 1, 11), propertyA, null);
        txn("b-expense", expenseAccount, LocalDate.of(2026, 1, 12), propertyB, null);

        List<FinancialTransaction> result = service.getTransactions(
                propertyA.getId(), null, AccountType.EXPENSE, null, null);

        assertThat(result)
                .extracting(FinancialTransaction::getDescription)
                .containsExactly("a-expense");
    }

    @Test
    void singleSidedDateRange_isApplied() {
        txn("jan", incomeAccount, LocalDate.of(2026, 1, 10), propertyA, null);
        txn("feb", incomeAccount, LocalDate.of(2026, 2, 10), propertyA, null);
        txn("mar", incomeAccount, LocalDate.of(2026, 3, 10), propertyA, null);

        List<FinancialTransaction> fromFeb = service.getTransactions(
                null, null, null, LocalDate.of(2026, 2, 1), null);
        // Date-descending, matching the old full-range queries.
        assertThat(fromFeb)
                .extracting(FinancialTransaction::getDescription)
                .containsExactly("mar", "feb");

        List<FinancialTransaction> untilJan = service.getTransactions(
                null, null, null, null, LocalDate.of(2026, 1, 31));
        assertThat(untilJan)
                .extracting(FinancialTransaction::getDescription)
                .containsExactly("jan");
    }

    @Test
    void splitChildren_excludedFromListViews_includedInUnitDrillDown() {
        FinancialTransaction parent = txn(
                "split-parent", expenseAccount, LocalDate.of(2026, 4, 1), propertyA, null);
        parent.setSplitParent(true);
        parent = transactionRepository.save(parent);

        FinancialTransaction child = txn(
                "split-child", expenseAccount, LocalDate.of(2026, 4, 1), propertyA, unitA);
        child.setParentTransaction(parent);
        transactionRepository.save(child);

        List<FinancialTransaction> all = service.getTransactions(null, null, null, null, null);
        assertThat(all)
                .extracting(FinancialTransaction::getDescription)
                .containsExactly("split-parent");

        List<FinancialTransaction> unitLedger = service.getTransactions(
                null, unitA.getId(), null, null, null);
        assertThat(unitLedger)
                .extracting(FinancialTransaction::getDescription)
                .containsExactly("split-child");
    }

    private Account seedAccount(String code, String name, AccountType type) {
        Account a = new Account();
        a.setCode(code);
        a.setName(name);
        a.setAccountType(type);
        return accountRepository.save(a);
    }

    private Property seedProperty(String nameEn) {
        Property p = new Property();
        p.setNameEn(nameEn);
        p.setEmirate(Emirate.DUBAI);
        return propertyRepository.save(p);
    }

    private FinancialTransaction txn(String description, Account account, LocalDate date,
                                     Property property, Unit unit) {
        FinancialTransaction t = new FinancialTransaction();
        t.setDescription(description);
        t.setAccount(account);
        t.setDate(date);
        t.setProperty(property);
        t.setUnit(unit);
        t.setDebit(BigDecimal.TEN);
        t.setCredit(BigDecimal.ZERO);
        return transactionRepository.save(t);
    }
}
