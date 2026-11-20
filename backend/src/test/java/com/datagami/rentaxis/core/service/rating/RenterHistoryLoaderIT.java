package com.datagami.rentaxis.core.service.rating;

import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import com.datagami.rentaxis.testsupport.TestIdentities;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@org.springframework.context.annotation.Import(com.datagami.rentaxis.testsupport.LaterBusinessDayConfig.class)
class RenterHistoryLoaderIT extends AbstractPostgresIT {

    @Autowired RenterHistoryLoader loader;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService generation;
    @Autowired LeasePostingService posting;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;

    private static final LocalDate SINCE = LocalDate.of(2024, 1, 1);

    private int seq;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private LeaseTestFixtures newOrgFixtures() {
        return new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);
    }

    private UUID postedLeaseId(LeaseTestFixtures f) {
        seq++;
        PostLeaseResponse posted = f.postedLease(LocalDate.of(2026, 9, 16), LocalDate.of(2026, 10, 2),
                LocalDate.of(2027, 10, 1), List.of(line("RENT", "51000")), 4, "10" + (1000 + seq * 10));
        return posted.lease().getId();
    }

    private List<UUID> chequeIds(UUID tenantId, UUID leaseId) {
        return jdbc.queryForList("select id from cheques where tenant_id = ? and lease_id = ? order by seq_no",
                UUID.class, tenantId, leaseId);
    }

    private record Seeded(UUID tenantId, UUID renterId, UUID leaseId, UUID chequeId, UUID penaltyId,
                          UUID writeOffId, UUID meetingId, UUID opportunityId) {}

    private Seeded seedFull(LeaseTestFixtures f) {
        UUID tenantId = f.tenantId();
        UUID leaseId = postedLeaseId(f);
        UUID renterId = f.renter().getId();
        UUID chequeId = chequeIds(tenantId, leaseId).get(0);
        jdbc.update("update cheques set status = 'BOUNCED', bounced_at = ? where id = ?",
                LocalDate.of(2026, 10, 20), chequeId);

        // Production shape of a system-proposed cheque-return fine (PenaltyRuleEngine.onBounce ->
        // PenaltyAssessmentService.proposeBySystem): the cheque is on cheque_id, source_type/source_id are null.
        UUID penaltyId = UUID.randomUUID();
        jdbc.update("insert into penalty_assessments (id, tenant_id, lease_id, cheque_id, renter_id, property_id, "
                        + "reason, amount, status, proposed_at, vatable, vat_amount, incident_date, source_type, source_id) "
                        + "values (?,?,?,?,?,?, 'CHEQUE_RETURN', 100, 'APPROVED', now(), false, 0, ?, null, null)",
                penaltyId, tenantId, leaseId, chequeId, renterId, f.property().getId(),
                LocalDate.of(2026, 10, 25));

        UUID writeOffId = UUID.randomUUID();
        jdbc.update("insert into bad_debt_write_offs (id, tenant_id, lease_id, renter_id, amount, write_off_date, "
                        + "reason, vat_lease, status, proposed_at) "
                        + "values (?,?,?,null,100,?, 'r', false, 'WRITTEN_OFF', now())",
                writeOffId, tenantId, leaseId, LocalDate.of(2026, 11, 1));

        jdbc.update("insert into lease_events (id, tenant_id, lease_id, previous_state, new_state, created_at) "
                        + "values (?,?,?, 'ACTIVE', 'ON_HOLD', ?)",
                UUID.randomUUID(), tenantId, leaseId, java.sql.Timestamp.from(Instant.parse("2026-10-10T08:00:00Z")));

        UUID host = TestIdentities.user(userRepo, UserRole.TENANT_ADMIN, tenantId);
        UUID meetingId = UUID.randomUUID();
        jdbc.update("insert into meetings (id, tenant_id, type, status, purpose, slot_start, slot_end, host_user_id, "
                        + "requester_user_id, lease_id, version, created_at, updated_at) "
                        + "values (?,?, 'OFFICE_VISIT', 'NO_SHOW', 'OTHER', ?, ?, ?, ?, ?, 0, now(), now())",
                meetingId, tenantId,
                java.sql.Timestamp.from(Instant.parse("2026-11-05T08:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-11-05T09:00:00Z")), host, host, leaseId);
        UUID opportunityId = UUID.randomUUID();
        jdbc.update("insert into renewal_opportunities (id, tenant_id, lease_id, stage, intent, intent_captured_at, "
                        + "opened_at, closed_at, outcome) values (?,?,?, 'CLOSED_WON', 'RENEW', ?, ?, ?, 'RENEWED')",
                opportunityId, tenantId, leaseId,
                java.sql.Timestamp.from(Instant.parse("2026-10-12T08:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-10-01T08:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-10-15T08:00:00Z")));
        return new Seeded(tenantId, renterId, leaseId, chequeId, penaltyId, writeOffId, meetingId, opportunityId);
    }

    @Test
    void loadsAllSourcesForRenter() {
        Seeded s = seedFull(newOrgFixtures());

        RenterHistory h = loader.load(s.tenantId(), List.of(s.renterId()), SINCE).get(s.renterId());

        assertThat(h.leases()).extracting(RenterHistory.LeaseRow::id).containsExactly(s.leaseId());
        assertThat(h.cheques()).hasSize(4);
        RenterHistory.ChequeRow bounced = h.cheques().stream().filter(c -> c.id().equals(s.chequeId()))
                .findFirst().orElseThrow();
        assertThat(bounced.bouncedAt()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(h.penalties()).extracting(RenterHistory.PenaltyRow::id).containsExactly(s.penaltyId());
        assertThat(h.penalties().get(0).sourceChequeId()).isEqualTo(s.chequeId());
        assertThat(h.writeOffs()).extracting(RenterHistory.WriteOffRow::id).containsExactly(s.writeOffId());
        assertThat(h.leaseEvents()).extracting(RenterHistory.LeaseEventRow::newState).contains("ON_HOLD");
        assertThat(h.leaseEvents()).allMatch(e -> e.leaseId().equals(s.leaseId()));
        assertThat(h.opportunities()).extracting(RenterHistory.OpportunityRow::id).containsExactly(s.opportunityId());
        assertThat(h.meetings()).extracting(RenterHistory.MeetingRow::id).containsExactly(s.meetingId());
        assertThat(h.adjustments()).isEmpty();
    }

    @Test
    void importedClearedOnUsedWhenClearedAtIsNull() {
        LeaseTestFixtures f = newOrgFixtures();
        UUID leaseId = postedLeaseId(f);
        UUID chequeId = chequeIds(f.tenantId(), leaseId).get(0);
        // ck_cheques_imported_dates: CLEARED import needs a cleared date, plus a deposit date for PDC cheques.
        jdbc.update("update cheques set status = 'CLEARED', cleared_at = null, imported_status = 'CLEARED', "
                        + "imported_cleared_on = ?, "
                        + "imported_deposited_on = case when mode = 'PDC' then ?::date else null end where id = ?",
                LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 2), chequeId);

        RenterHistory h = loader.load(f.tenantId(), List.of(f.renter().getId()), SINCE).get(f.renter().getId());

        RenterHistory.ChequeRow row = h.cheques().stream().filter(c -> c.id().equals(chequeId)).findFirst().orElseThrow();
        assertThat(row.clearedAt()).isEqualTo(LocalDate.of(2026, 10, 3));
    }

    @Test
    void loaderNeverReturnsAnotherOrgsRows() {
        Seeded a = seedFull(newOrgFixtures());
        Seeded b = seedFull(newOrgFixtures());

        Map<UUID, RenterHistory> underA = loader.load(a.tenantId(), List.of(a.renterId(), b.renterId()), SINCE);
        RenterHistory home = underA.get(a.renterId());
        assertThat(home.leases()).extracting(RenterHistory.LeaseRow::id).containsExactly(a.leaseId());
        assertThat(home.leaseEvents()).isNotEmpty().allMatch(e -> e.leaseId().equals(a.leaseId()));
        assertThat(home.opportunities()).extracting(RenterHistory.OpportunityRow::id)
                .containsExactly(a.opportunityId());
        assertThat(home.meetings()).extracting(RenterHistory.MeetingRow::id).containsExactly(a.meetingId());
        RenterHistory leak = underA.get(b.renterId());
        assertThat(leak).isNotNull();
        assertThat(leak.leases()).isEmpty();
        assertThat(leak.cheques()).isEmpty();
        assertThat(leak.penalties()).isEmpty();
        assertThat(leak.writeOffs()).isEmpty();
        assertThat(leak.leaseEvents()).isEmpty();
        assertThat(leak.opportunities()).isEmpty();
        assertThat(leak.meetings()).isEmpty();

        UUID emptyOrg = TestIdentities.org(orgRepo);
        RenterHistory other = loader.load(emptyOrg, List.of(a.renterId()), SINCE).get(a.renterId());
        assertThat(other.leases()).isEmpty();
        assertThat(other.cheques()).isEmpty();
        assertThat(other.penalties()).isEmpty();
        assertThat(other.writeOffs()).isEmpty();
        assertThat(other.leaseEvents()).isEmpty();
        assertThat(other.meetings()).isEmpty();
        // loader restores the caller's context (here: org B, the last bootstrapped)
        assertThat(TenantContextHolder.getTenantId()).isEqualTo(b.tenantId());
    }

    @Test
    void draftOnlyRenterNotPaged() {
        LeaseTestFixtures f = newOrgFixtures();
        UUID rated = f.renter().getId();
        postedLeaseId(f);
        var draftRenter = f.createRenter("Draft Only");
        f.draftLease(f.createUnit(f.property(), "202"), draftRenter,
                LocalDate.of(2026, 9, 16), LocalDate.of(2026, 10, 2), LocalDate.of(2027, 10, 1),
                List.of(line("RENT", "51000")));

        List<UUID> ids = loader.ratedRenterIds(f.tenantId(), null, 10);

        assertThat(ids).containsExactly(rated);
    }

    @Test
    void pagesByKeyset() {
        LeaseTestFixtures f = newOrgFixtures();
        postedLeaseId(f);
        for (int i = 0; i < 2; i++) {
            var renter = f.createRenter("Renter " + i);
            var unit = f.createUnit(f.property(), "30" + i);
            f.postedLease(unit, renter, LocalDate.of(2026, 9, 16), LocalDate.of(2026, 10, 2),
                    LocalDate.of(2027, 10, 1), List.of(line("RENT", "51000")), 4, "20" + (1000 + i * 10));
        }

        List<UUID> first = loader.ratedRenterIds(f.tenantId(), null, 2);
        List<UUID> second = loader.ratedRenterIds(f.tenantId(), first.get(1), 2);

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(1);
        // Postgres orders uuid bytewise unsigned; Java compareTo is signed, so check disjoint + complete instead.
        assertThat(second).doesNotContainAnyElementsOf(first);
        assertThat(loader.ratedRenterIds(f.tenantId(), second.get(0), 2)).isEmpty();
    }

    @Test
    void queryCountBounded() {
        Seeded s = seedFull(newOrgFixtures());
        List<UUID> ids = new ArrayList<>();
        ids.add(s.renterId());
        for (int i = 0; i < 49; i++) ids.add(UUID.randomUUID());
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        long before = stats.getPrepareStatementCount();

        Map<UUID, RenterHistory> out = loader.load(s.tenantId(), ids, SINCE);

        long used = stats.getPrepareStatementCount() - before;
        assertThat(out).hasSize(50);
        assertThat(used).isLessThanOrEqualTo(10);
    }
}
