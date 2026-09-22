package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #326: {@code DELETE /api/admin/tenants/{id}} 500d as soon as the tenant
 * owned a POSTED lease.
 *
 * <p>Two things stand between the cascade and a ledger: the {@code leases} ↔
 * {@code journal_entries} foreign-key cycle (the lease points at its posting
 * journal, the journal carries the lease dimension), which no ordering of
 * tenant-scoped DELETEs can resolve; and the immutability triggers from
 * changeset 81, which refuse a DELETE on {@code journal_entries} and
 * {@code journal_lines} outright. The retry loop therefore made no forward
 * progress and bailed with "deleteTenant stalled after pass 1".</p>
 *
 * <p>The assertion is deliberately "no rows left anywhere", not just "no
 * exception": a purge that swallowed the ledger tables and left their rows
 * behind would orphan journals against a landlord_org that no longer exists.</p>
 */
@SpringBootTest
@Testcontainers
class TenantDeletionPostedLeaseIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired LandlordOrgService service;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService generation;
    @Autowired LeasePostingService posting;
    @Autowired JdbcTemplate jdbc;

    /** The post-commit document sweep talks to disk/Azure; nothing here is about that. */
    @MockitoBean ContractGenerationService contractGenerationService;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Test
    void deletesATenantWhoseLeaseIsPosted() {
        PostLeaseResponse posted = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, "100040");
        assertThat(posted.tcoJournalId()).isNotNull();

        UUID tenantId = fixtures.tenantId();
        LandlordOrg org = orgRepo.findById(tenantId).orElseThrow();

        // Sanity: the ledger this delete has to get past really exists.
        assertThat(rows("journal_entries", tenantId)).isPositive();
        assertThat(rows("journal_lines", tenantId)).isPositive();
        assertThat(rows("cheques", tenantId)).isPositive();
        assertThat(rows("leases", tenantId)).isPositive();

        // The super admin who presses Delete carries no tenant of their own.
        TenantContextHolder.clear();

        service.deleteTenant(tenantId, org.getName());

        assertThat(orgRepo.findById(tenantId)).isEmpty();
        for (String table : List.of("journal_entries", "journal_lines", "leases", "cheques",
                "lease_lines", "rent_segments", "recognition_entries", "properties", "units")) {
            assertThat(rows(table, tenantId)).as("rows surviving in %s", table).isZero();
        }

        // Nothing else's ledger may be touched, and the triggers that guard it must
        // be back on afterwards.
        assertThat(triggersEnabled("journal_entries")).isTrue();
        assertThat(triggersEnabled("journal_lines")).isTrue();
    }

    private long rows(String table, UUID tenantId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM \"" + table + "\" WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    /** Every user trigger on the table is enabled ('O' = origin, i.e. on). */
    private boolean triggersEnabled(String table) {
        Long disabled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid " +
                        "WHERE c.relname = ? AND NOT t.tgisinternal AND t.tgenabled = 'D'",
                Long.class, table);
        return disabled != null && disabled == 0;
    }
}
