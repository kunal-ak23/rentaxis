package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EntryNumberServiceIT extends AbstractPostgresIT {

    @Autowired EntryNumberService service;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    UUID tenantId;

    @BeforeEach void tenant() {
        LandlordOrg org = new LandlordOrg(); org.setName("Seq-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
    }
    @AfterEach void clear() { TenantContextHolder.clear(); }

    @Test
    void numbersArePrefixYearSlashSequencePerDocType() {
        assertThat(service.next(JournalDocType.TCO, LocalDate.of(2026, 9, 16))).isEqualTo("TCO-26/1");
        assertThat(service.next(JournalDocType.TCO, LocalDate.of(2026, 9, 17))).isEqualTo("TCO-26/2");
        assertThat(service.next(JournalDocType.PDR, LocalDate.of(2026, 9, 17))).isEqualTo("PDR-26/1");
        assertThat(service.next(JournalDocType.TCO, LocalDate.of(2027, 1, 5))).isEqualTo("TCO-27/1");
    }

    /**
     * Numbering needs the tenant's fiscal-settings row, which is created on first
     * access — and first access is frequently a read, since {@code fiscalYearOf} and
     * {@code assertOpen} are read-only. Postgres refuses an INSERT on a read-only
     * connection, so the creation has to escape the caller's transaction.
     */
    @Test
    void fiscalSettingsAreCreatedOnFirstAccessFromAReadOnlyTransaction() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        Integer fiscalYear = readOnly.execute(status -> fiscal.fiscalYearOf(LocalDate.of(2026, 9, 17)));

        assertThat(fiscalYear).isEqualTo(2026);
        assertThat(jdbc.queryForObject("select count(*) from tenant_fiscal_settings where tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(1);
    }

    @Test
    void concurrentCallersNeverShareANumber() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<String>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            futures.add(pool.submit(() -> {
                TenantContextHolder.setTenantId(tenantId);
                try { return service.next(JournalDocType.JV, LocalDate.of(2026, 9, 17)); }
                finally { TenantContextHolder.clear(); }
            }));
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Future<String> f : futures) seen.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(seen).hasSize(40);
    }
}
