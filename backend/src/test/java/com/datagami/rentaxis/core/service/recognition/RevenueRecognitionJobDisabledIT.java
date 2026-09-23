package com.datagami.rentaxis.core.service.recognition;

import com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The kill switch, bound from the real property.
 *
 * <p>A context of its own because that is the only way to assert the thing that
 * actually matters: not the {@code if (!enabled)} branch — which a reflection
 * poke would reach — but that <b>{@code rentaxis.recognition.job.enabled} is the
 * key the field is wired to</b>. A kill switch spelled one way in
 * {@code application.yml} and another in the {@code @Value} is a switch that does
 * nothing, and it fails in exactly the situation it exists for: an operator
 * turning off a job that is writing bad journals at 00:30 and watching it keep
 * going.</p>
 *
 * <p>The other half of the ruling is asserted here too: {@code runFor} is
 * <em>not</em> gated. Turning the nightly pass off is how an operator takes the
 * close into their own hands, not how they lose the ability to close at all.</p>
 */
@SpringBootTest(properties = "rentaxis.recognition.job.enabled=false")
@Import(RevenueRecognitionJobDisabledIT.FixedClockConfig.class)
class RevenueRecognitionJobDisabledIT extends AbstractPostgresIT {

    static final LocalDate TODAY = LocalDate.of(2026, 12, 1);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).plusHours(3).toInstant(), ZoneOffset.UTC);
        }
    }

    @Autowired RevenueRecognitionJob job;
    @Autowired RecognitionService recognition;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService cheques;
    @Autowired LeasePostingService posting;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;
    private UUID leaseId;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, cheques, posting);
        leaseId = fixtures.postedLease(LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 24),
                LocalDate.of(2027, 9, 23),
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, null)
                .lease().getId();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private <T> T as(UUID tenantId, Supplier<T> read) {
        TenantContextHolder.setTenantId(tenantId);
        try {
            return read.get();
        } finally {
            TenantContextHolder.clear();
        }
    }

    /**
     * This test's own landlord only. The container outlives the method, so a global
     * count would be the previous test's three CILs — which is exactly what it was,
     * before this took a tenant id.
     */
    private long cilCount() {
        return jdbc.queryForObject("select count(*) from journal_entries "
                + "where doc_type = 'CIL' and tenant_id = ?", Long.class, fixtures.tenantId());
    }

    private List<RecognitionEntryDTO> schedule() {
        return as(fixtures.tenantId(), () -> recognition.scheduleFor(leaseId));
    }

    @Test
    void theNightlyPassDoesNothingWhileTheSwitchIsOff() {
        job.run();

        assertThat(schedule()).as("13 rows, every one untouched")
                .hasSize(13)
                .allSatisfy(r -> {
                    assertThat(r.status()).isEqualTo(RecognitionStatus.PLANNED);
                    assertThat(r.journalId()).isNull();
                });
        assertThat(cilCount()).isZero();
    }

    /** The manual close is how an operator finishes the month with the trigger off. */
    @Test
    void theManualPassStillWorks() {
        job.runFor(TODAY);

        assertThat(schedule().subList(0, 3))
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(RecognitionStatus.POSTED));
        assertThat(cilCount()).isEqualTo(3L);
    }
}
