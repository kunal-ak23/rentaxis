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
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
 * Counts the SQL the transaction list actually issues.
 *
 * <p>{@code FinancialTransaction} has five EAGER {@code @ManyToOne}
 * associations. For a Criteria query Hibernate does not fold those into the
 * base select — it issues one secondary SELECT per association per row, so
 * listing N transactions cost roughly 1 + 5N round trips. On a tenant with a
 * few thousand rows of ordinary financial history, one unfiltered GET was tens
 * of thousands of queries against a 20-connection pool.</p>
 *
 * <p>This asserts on the statement count rather than on a returned list,
 * because the list was always correct — only the cost was wrong. A test that
 * checked the data would have passed before and after and proved nothing.</p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
class FinancialTransactionQueryCountIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired FinancialTransactionService service;
    @Autowired FinancialTransactionRepository transactionRepository;
    @Autowired LandlordOrgRepository landlordOrgRepository;
    @Autowired PropertyRepository propertyRepository;
    @Autowired UnitRepository unitRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired EntityManagerFactory entityManagerFactory;

    private static final int ROWS = 25;

    private Statistics stats;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("TxnCount-" + UUID.randomUUID());
        TenantContextHolder.setTenantId(landlordOrgRepository.save(org).getId());

        // A DISTINCT account and property per row. This is the whole reason the
        // test has teeth: with one shared account, Hibernate loads it once and
        // serves the other rows from the session cache, so N+1 never appears
        // however broken the fetching is. An earlier version of this test did
        // exactly that and passed with both fixes removed.
        for (int i = 0; i < ROWS; i++) {
            Account account = new Account();
            account.setCode("C-%02d-%02d".formatted(i / 10, i % 10));
            account.setName("Income " + i);
            account.setAccountType(AccountType.INCOME);
            account = accountRepository.save(account);

            Property property = new Property();
            property.setNameEn("Count-Property-" + i);
            property.setEmirate(Emirate.DUBAI);
            property = propertyRepository.save(property);

            FinancialTransaction t = new FinancialTransaction();
            t.setDescription("txn-" + i);
            t.setAccount(account);
            t.setProperty(property);
            // Left null deliberately: the association is optional in practice,
            // and a LEFT fetch join has to keep the row rather than drop it.
            t.setUnit(null);
            t.setDate(LocalDate.of(2026, 1, 1).plusDays(i));
            t.setDebit(BigDecimal.TEN);
            t.setCredit(BigDecimal.ZERO);
            transactionRepository.save(t);
        }

        stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void listingTransactionsDoesNotScaleQueriesWithRowCount() {
        List<FinancialTransaction> result = service.getTransactions(null, null, null, null, null);

        assertThat(result).hasSize(ROWS);

        long queries = stats.getPrepareStatementCount();
        // Before the fetch joins this was ~1 + 5*25 = 126. The bound is
        // deliberately far below that and far above 1: the point is that the
        // count does not grow with the number of rows, not to pin an exact
        // number that a future join would legitimately change.
        assertThat(queries)
                .as("queries issued for %d transactions", ROWS)
                .isLessThan(10);
    }

    @Test
    void rowsWithNoUnitAreStillReturned() {
        // A fetch join that defaulted to INNER would silently drop every
        // transaction without a unit, vendor or staff member — most of them.
        List<FinancialTransaction> result = service.getTransactions(null, null, null, null, null);

        assertThat(result).hasSize(ROWS);
        assertThat(result).allSatisfy(t -> assertThat(t.getUnit()).isNull());
    }

    @Test
    void associationsAreActuallyPopulated() {
        // The fetch has to load the associations, not merely join them.
        List<FinancialTransaction> result = service.getTransactions(null, null, null, null, null);

        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(t -> {
            assertThat(t.getAccount()).isNotNull();
            assertThat(t.getAccount().getCode()).startsWith("C-");
            assertThat(t.getProperty()).isNotNull();
            assertThat(t.getProperty().getNameEn()).startsWith("Count-Property-");
        });
    }
}
