package com.datagami.rentaxis.core.service.rating;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.rating.RatingEventDTO;
import com.datagami.rentaxis.api.dto.rating.RenterRatingDetailDTO;
import com.datagami.rentaxis.api.dto.rating.RenterRatingRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.penalty.PenaltyAssessmentService;
import com.datagami.rentaxis.core.service.renewal.RenewalMarkService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrgFineSettings;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.PenaltyAssessmentStatus;
import com.datagami.rentaxis.domain.entity.enums.RatingGrade;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgFineSettingsRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import com.datagami.rentaxis.testsupport.TestIdentities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@org.springframework.context.annotation.Import(com.datagami.rentaxis.testsupport.LaterBusinessDayConfig.class)
class RenterRatingServiceIT extends AbstractPostgresIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final LocalDate BOUNCE_DAY = LocalDate.of(2026, 10, 20);

    @Autowired RenterRatingService service;
    @Autowired RenterRatingJob job;
    @Autowired TenantFeatureService tenantFeatureService;
    @Autowired JdbcTemplate jdbc;
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
    @Autowired ChequeService chequeService;
    @Autowired PenaltyAssessmentService penaltyService;
    @Autowired LandlordOrgFineSettingsRepository fineSettingsRepo;
    @Autowired TransactionTemplate tx;
    @Autowired RenewalMarkService renewalMarks;

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

    private UUID postedLease(LeaseTestFixtures f) {
        seq++;
        return f.postedLease(LocalDate.of(2026, 9, 16), LocalDate.of(2026, 10, 2),
                LocalDate.of(2027, 10, 1), List.of(line("RENT", "51000")), 4, "10" + (1000 + seq * 10))
                .lease().getId();
    }

    private UUID postedLeaseFor(LeaseTestFixtures f, String name) {
        seq++;
        var renter = f.createRenter(name);
        var unit = f.createUnit(f.property(), "U" + seq);
        return f.postedLease(unit, renter, LocalDate.of(2026, 9, 16), LocalDate.of(2026, 10, 2),
                LocalDate.of(2027, 10, 1), List.of(line("RENT", "51000")), 4, "30" + (1000 + seq * 10))
                .lease().getId();
    }

    private List<UUID> chequeIds(UUID tenantId, UUID leaseId) {
        return jdbc.queryForList("select id from cheques where tenant_id = ? and lease_id = ? order by seq_no",
                UUID.class, tenantId, leaseId);
    }

    private UUID bounce(UUID tenantId, UUID leaseId, int index, LocalDate on) {
        UUID chequeId = chequeIds(tenantId, leaseId).get(index);
        jdbc.update("update cheques set status = 'BOUNCED', bounced_at = ? where id = ?", on, chequeId);
        return chequeId;
    }

    private int storedRows(UUID tenantId, UUID renterId) {
        return jdbc.queryForObject("select count(*) from renter_ratings where tenant_id = ? and renter_id = ?",
                Integer.class, tenantId, renterId);
    }

    private int storedScore(UUID tenantId, UUID renterId) {
        return jdbc.queryForObject("select score from renter_ratings where tenant_id = ? and renter_id = ?",
                Integer.class, tenantId, renterId);
    }

    @Test
    void liveMatchesEngine() {
        LeaseTestFixtures f = newOrgFixtures();
        UUID leaseId = postedLease(f);
        UUID chequeId = bounce(f.tenantId(), leaseId, 0, BOUNCE_DAY);

        RenterRatingDetailDTO d = service.live(f.tenantId(), f.renter().getId(), TODAY);

        assertThat(d.score()).isEqualTo(55);
        assertThat(d.grade()).isEqualTo(RatingGrade.C);
        assertThat(d.base()).isEqualTo(70);
        assertThat(d.hasStartedContract()).isTrue();
        assertThat(d.deductionsCount()).isEqualTo(1);
        assertThat(d.events()).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(RatingEventType.BOUNCE);
            assertThat(e.sourceId()).isEqualTo(chequeId);
        });
        assertThat(d.trend()).hasSize(1);
        assertThat(d.trend().get(0).score()).isEqualTo(55);
    }

    /**
     * PR round 1 C1: the cheque-return fine the system proposes on a bounce links the cheque through
     * {@code cheque_id} (source_type/source_id stay null). Driven through the real register path,
     * ChequeService.bounce -> PenaltyRuleEngine.onBounce -> PenaltyAssessmentService.proposeBySystem,
     * then approved: the bounce counts once (-15) and the fine is not a second, misconduct deduction.
     */
    @Test
    void systemProposedChequeReturnFineIsNotDoubleCounted() {
        LeaseTestFixtures f = newOrgFixtures();
        tx.executeWithoutResult(s -> {
            LandlordOrgFineSettings o = fineSettingsRepo.findByLandlordOrgId(f.tenantId()).orElseGet(() -> {
                LandlordOrgFineSettings n = new LandlordOrgFineSettings();
                n.setLandlordOrgId(f.tenantId());
                n.setTenantId(f.tenantId());
                n.setFineBounceAmount(new BigDecimal("500"));
                n.setFineSignatureMismatchAmount(new BigDecimal("500"));
                n.setFineAccountClosedAmount(new BigDecimal("1000"));
                n.setFineGraceDays(7);
                n.setFinePerDayRate(new BigDecimal("25"));
                n.setAutoProposeLatePayment(false);
                return n;
            });
            o.setBouncesBeforePenalty(1);
            o.setAutoProposeChequeReturn(true);
            fineSettingsRepo.save(o);
        });
        UUID leaseId = postedLease(f);
        UUID chequeId = chequeIds(f.tenantId(), leaseId).get(0);
        LocalDate bounceDay = LocalDate.of(2026, 10, 12);
        chequeService.deposit(chequeId, ChequeActionRequest.on(LocalDate.of(2026, 10, 5)));
        chequeService.bounce(chequeId, new ChequeActionRequest(bounceDay, null, ChequeFailureReason.BOUNCE, null));

        List<java.util.Map<String, Object>> fines = jdbc.queryForList(
                "select id, cheque_id, source_type, source_id from penalty_assessments "
                        + "where tenant_id = ? and lease_id = ? and reason = 'CHEQUE_RETURN'", f.tenantId(), leaseId);
        assertThat(fines).as("the rule proposed exactly one fine").hasSize(1);
        assertThat(fines.get(0).get("cheque_id")).isEqualTo(chequeId);
        assertThat(fines.get(0).get("source_type")).isNull();
        assertThat(fines.get(0).get("source_id")).isNull();
        UUID fineId = (UUID) fines.get(0).get("id");
        assertThat(penaltyService.approve(fineId, LocalDate.of(2026, 10, 20)).status())
                .isEqualTo(PenaltyAssessmentStatus.APPROVED);

        RenterRatingDetailDTO d = service.live(f.tenantId(), f.renter().getId(), TODAY);

        assertThat(d.events()).filteredOn(e -> e.appliedPoints() < 0).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(RatingEventType.BOUNCE);
            assertThat(e.appliedPoints()).isEqualTo(-15.0);
            assertThat(e.sourceId()).isEqualTo(chequeId);
        });
        assertThat(d.events()).noneMatch(e -> e.type() == RatingEventType.MISCONDUCT_PENALTY);
        assertThat(d.deductionsCount()).isEqualTo(1);
        assertThat(d.score()).isEqualTo(55);
    }

    /**
     * Round 2 I1: the not-renewing mark (RenewalMarkService.markNotRenewing, a real production path) writes an
     * ON_HOLD -> ON_HOLD lease event in the middle of a hold. The hold is still one 20-day hold: one LONG_HOLD.
     * The mark is stamped "now", so the score is taken 10 days ahead and the hold started 10 days ago.
     */
    @Test
    void notRenewingMarkDoesNotSplitALongHold() {
        LeaseTestFixtures f = newOrgFixtures();
        UUID leaseId = postedLease(f);
        LocalDate now = LocalDate.now(com.datagami.rentaxis.config.AppTimeZone.DEFAULT);
        LocalDate holdStart = now.minusDays(10);
        jdbc.update("update leases set status = 'ON_HOLD' where id = ?", leaseId);
        jdbc.update("insert into lease_events (id, tenant_id, lease_id, previous_state, new_state, created_at) "
                        + "values (?,?,?, 'ACTIVE', 'ON_HOLD', ?)", UUID.randomUUID(), f.tenantId(), leaseId,
                java.sql.Timestamp.from(holdStart.atTime(9, 0)
                        .atZone(com.datagami.rentaxis.config.AppTimeZone.DEFAULT).toInstant()));

        renewalMarks.markNotRenewing(leaseId, "Moving out", TestIdentities.user(userRepo, UserRole.TENANT_ADMIN,
                f.tenantId()));
        assertThat(jdbc.queryForObject("select count(*) from lease_events where lease_id = ? "
                + "and previous_state = 'ON_HOLD' and new_state = 'ON_HOLD'", Integer.class, leaseId)).isEqualTo(1);

        RenterRatingDetailDTO d = service.live(f.tenantId(), f.renter().getId(), now.plusDays(10));

        assertThat(d.events()).filteredOn(e -> e.type() == RatingEventType.LONG_HOLD).singleElement()
                .satisfies(e -> {
                    assertThat(e.appliedPoints()).isEqualTo(-10.0);
                    assertThat(e.date()).isEqualTo(holdStart.plusDays(15));
                });
    }

    @Test
    void liveRefusesRenterOfAnotherOrg() {
        LeaseTestFixtures a = newOrgFixtures();
        postedLease(a);
        LeaseTestFixtures b = newOrgFixtures();
        postedLease(b);

        assertThatThrownBy(() -> service.live(a.tenantId(), b.renter().getId(), TODAY))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void storeWritesOnceThenSkipsUnchanged() {
        LeaseTestFixtures f = newOrgFixtures();
        UUID leaseId = postedLease(f);
        bounce(f.tenantId(), leaseId, 0, BOUNCE_DAY);
        UUID renterId = f.renter().getId();

        assertThat(service.recomputeAndStore(f.tenantId(), List.of(renterId), TODAY)).isEqualTo(1);
        assertThat(service.recomputeAndStore(f.tenantId(), List.of(renterId), TODAY)).isEqualTo(0);
        assertThat(storedScore(f.tenantId(), renterId)).isEqualTo(55);
        String json = jdbc.queryForObject("select events_json from renter_ratings where renter_id = ?",
                String.class, renterId);
        assertThat(json).contains("BOUNCE");
    }

    @Test
    void storeRewritesWhenAgeWeightChanges() {
        LeaseTestFixtures f = newOrgFixtures();
        UUID leaseId = postedLease(f);
        bounce(f.tenantId(), leaseId, 0, BOUNCE_DAY);
        UUID renterId = f.renter().getId();
        LocalDate day365 = BOUNCE_DAY.plusDays(365);

        assertThat(service.recomputeAndStore(f.tenantId(), List.of(renterId), day365)).isEqualTo(1);
        assertThat(storedScore(f.tenantId(), renterId)).isEqualTo(55);
        assertThat(service.recomputeAndStore(f.tenantId(), List.of(renterId), day365.plusDays(1))).isEqualTo(1);
        assertThat(storedScore(f.tenantId(), renterId)).isEqualTo(63);
    }

    @Test
    void storeDeletesRowsOfRentersNoLongerRated() {
        LeaseTestFixtures f = newOrgFixtures();
        UUID leaseId = postedLease(f);
        UUID renterId = f.renter().getId();
        service.recomputeAndStore(f.tenantId(), List.of(renterId), TODAY);
        assertThat(storedRows(f.tenantId(), renterId)).isEqualTo(1);

        jdbc.update("update leases set status = 'DRAFT' where id = ?", leaseId);
        service.recomputeAndStore(f.tenantId(), List.of(renterId), TODAY);

        assertThat(storedRows(f.tenantId(), renterId)).isZero();
    }

    @Test
    void snapshotOnFirstOfMonth() {
        LeaseTestFixtures f = newOrgFixtures();
        postedLease(f);
        UUID renterId = f.renter().getId();
        LocalDate first = LocalDate.of(2026, 12, 1);

        job.runNowForTenant(f.tenantId(), first);
        job.runNowForTenant(f.tenantId(), first);

        assertThat(jdbc.queryForObject(
                "select count(*) from renter_rating_snapshots where tenant_id = ? and renter_id = ? and month = ?",
                Integer.class, f.tenantId(), renterId, first)).isEqualTo(1);
        job.runNowForTenant(f.tenantId(), LocalDate.of(2026, 12, 2));
        assertThat(jdbc.queryForObject("select count(*) from renter_rating_snapshots where renter_id = ?",
                Integer.class, renterId)).isEqualTo(1);
        assertThat(service.live(f.tenantId(), renterId, LocalDate.of(2026, 12, 2)).trend()).hasSize(2);
    }

    /** M3: a month whose 1st was missed still gets a snapshot, written on the first run that month. */
    @Test
    void snapshotWrittenMidMonthWhenMonthHasNoneAndKeptAfterwards() {
        LeaseTestFixtures f = newOrgFixtures();
        UUID leaseId = postedLease(f);
        UUID renterId = f.renter().getId();
        LocalDate month = LocalDate.of(2026, 12, 1);

        job.runNowForTenant(f.tenantId(), LocalDate.of(2026, 12, 15));

        assertThat(jdbc.queryForObject(
                "select score from renter_rating_snapshots where tenant_id = ? and renter_id = ? and month = ?",
                Integer.class, f.tenantId(), renterId, month)).isEqualTo(70);

        // a later run in the same month keeps the month's snapshot (one row, first value)
        bounce(f.tenantId(), leaseId, 0, LocalDate.of(2026, 12, 16));
        job.runNowForTenant(f.tenantId(), LocalDate.of(2026, 12, 17));
        assertThat(storedScore(f.tenantId(), renterId)).isEqualTo(55);
        assertThat(jdbc.queryForList("select score from renter_rating_snapshots where tenant_id = ? and renter_id = ?",
                Integer.class, f.tenantId(), renterId)).containsExactly(70);
    }

    /**
     * M1: another writer inserts the renter's row after this store read "no row" and before it writes. The
     * store's insert waits on the other transaction's uncommitted row, then must update it, not fail on the
     * unique key. The other transaction commits only once this one is seen blocked on that lock.
     */
    @Test
    void storeUpsertsWhenAnotherWriterInsertsTheRowFirst() throws Exception {
        LeaseTestFixtures f = newOrgFixtures();
        postedLease(f);
        UUID tenantId = f.tenantId();
        UUID renterId = f.renter().getId();
        java.util.concurrent.CountDownLatch inserted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> other = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.update("insert into renter_ratings (id, tenant_id, renter_id, score, grade, deductions_count, "
                                + "events_json, fingerprint, computed_at) "
                                + "values (?,?,?, 1, 'D', 0, '[]', 'other', now())",
                        UUID.randomUUID(), tenantId, renterId);
                inserted.countDown();
                try {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(inserted.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            java.util.concurrent.Future<Integer> mine = pool.submit(() -> {
                TenantContextHolder.setTenantId(tenantId);
                try {
                    return service.recomputeAndStore(tenantId, List.of(renterId), TODAY);
                } finally {
                    TenantContextHolder.clear();
                }
            });
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline && jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where datname = current_database() "
                            + "and wait_event_type = 'Lock' and query like 'insert into renter_ratings%'",
                    Integer.class) == 0) {
                Thread.sleep(20);
            }
            release.countDown();
            other.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(mine.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(storedRows(tenantId, renterId)).isEqualTo(1);
        assertThat(storedScore(tenantId, renterId)).isEqualTo(70);
        assertThat(jdbc.queryForObject("select fingerprint from renter_ratings where tenant_id = ? and renter_id = ?",
                String.class, tenantId, renterId)).isNotEqualTo("other");
    }

    @Test
    void jobSkipsOrgsWithFeatureOff() {
        LeaseTestFixtures f = newOrgFixtures();
        postedLease(f);

        job.runNow(TODAY);

        assertThat(storedRows(f.tenantId(), f.renter().getId())).isZero();
    }

    @Test
    void enablingFeatureComputesOrg() throws Exception {
        LeaseTestFixtures f = newOrgFixtures();
        postedLease(f);
        UUID renterId = f.renter().getId();
        assertThat(storedRows(f.tenantId(), renterId)).isZero();

        tenantFeatureService.setEnabled(f.tenantId(), TenantFeature.RENTER_RATING, true);

        long deadline = System.currentTimeMillis() + 10_000;
        while (storedRows(f.tenantId(), renterId) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(storedRows(f.tenantId(), renterId)).isEqualTo(1);
    }

    @Test
    void adjustmentValidation() {
        LeaseTestFixtures f = newOrgFixtures();
        postedLease(f);
        UUID user = TestIdentities.user(userRepo, UserRole.TENANT_ADMIN, f.tenantId());
        UUID renterId = f.renter().getId();

        for (int bad : new int[]{0, 31, -31}) {
            assertThatThrownBy(() -> service.addAdjustment(f.tenantId(), renterId, bad, "ok", user, TODAY))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }
        assertThatThrownBy(() -> service.addAdjustment(f.tenantId(), renterId, 5, "   ", user, TODAY))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> service.addAdjustment(f.tenantId(), renterId, 5, "x".repeat(501), user, TODAY))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void adjustmentRefusedWhenNoStartedContract() {
        LeaseTestFixtures f = newOrgFixtures();
        UUID leaseId = postedLease(f);
        jdbc.update("update leases set status = 'DRAFT' where id = ?", leaseId);
        UUID user = TestIdentities.user(userRepo, UserRole.TENANT_ADMIN, f.tenantId());
        UUID renterId = f.renter().getId();

        assertThatThrownBy(() -> service.addAdjustment(f.tenantId(), renterId, 5, "reason", user, TODAY))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("no started contract");
        assertThat(service.live(f.tenantId(), renterId, TODAY).hasStartedContract()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from renter_rating_adjustments where renter_id = ?",
                Integer.class, renterId)).isZero();
    }

    @Test
    void adjustmentAddAndRemove() {
        LeaseTestFixtures f = newOrgFixtures();
        postedLease(f);
        UUID user = TestIdentities.user(userRepo, UserRole.TENANT_ADMIN, f.tenantId());
        UUID renterId = f.renter().getId();

        RatingEventDTO ev = service.addAdjustment(f.tenantId(), renterId, 10, "  Good reference  ", user, TODAY);

        assertThat(ev.type()).isEqualTo(RatingEventType.ADJUSTMENT);
        assertThat(ev.adjustmentId()).isNotNull();
        assertThat(ev.note()).isEqualTo("Good reference");
        assertThat(ev.createdByName()).isEqualTo("TENANT_ADMIN");
        RenterRatingDetailDTO live = service.live(f.tenantId(), renterId, TODAY);
        assertThat(live.events()).filteredOn(e -> e.type() == RatingEventType.ADJUSTMENT).singleElement()
                .satisfies(e -> assertThat(e.createdByName()).isEqualTo("TENANT_ADMIN"));
        assertThat(live.events()).filteredOn(e -> e.type() != RatingEventType.ADJUSTMENT)
                .allMatch(e -> e.createdByName() == null);
        assertThat(service.live(f.tenantId(), renterId, TODAY).score()).isEqualTo(80);
        assertThat(storedScore(f.tenantId(), renterId)).isEqualTo(80);

        service.removeAdjustment(f.tenantId(), renterId, ev.adjustmentId(), user, TODAY);

        assertThat(service.live(f.tenantId(), renterId, TODAY).score()).isEqualTo(70);
        assertThat(storedScore(f.tenantId(), renterId)).isEqualTo(70);
        assertThat(jdbc.queryForObject(
                "select removed_at is not null and removed_by = ? from renter_rating_adjustments where id = ?",
                Boolean.class, user, ev.adjustmentId())).isTrue();
    }

    @Test
    void removeAdjustmentOfAnotherRenterIsNotFound() {
        LeaseTestFixtures f = newOrgFixtures();
        postedLease(f);
        UUID user = TestIdentities.user(userRepo, UserRole.TENANT_ADMIN, f.tenantId());
        RatingEventDTO ev = service.addAdjustment(f.tenantId(), f.renter().getId(), 5, "r", user, TODAY);

        assertThatThrownBy(() -> service.removeAdjustment(f.tenantId(), UUID.randomUUID(), ev.adjustmentId(), user, TODAY))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void listFiltersByGradeAndSearch() {
        LeaseTestFixtures f = newOrgFixtures();
        UUID user = TestIdentities.user(userRepo, UserRole.TENANT_ADMIN, f.tenantId());
        UUID leaseD = postedLease(f);
        bounce(f.tenantId(), leaseD, 0, BOUNCE_DAY);
        bounce(f.tenantId(), leaseD, 1, BOUNCE_DAY);
        UUID renterD = f.renter().getId();
        postedLeaseFor(f, "Zed Alpha");
        UUID renterA = jdbc.queryForObject(
                "select id from renters where tenant_id = ? and name_en = 'Zed Alpha'", UUID.class, f.tenantId());
        service.addAdjustment(f.tenantId(), renterA, 20, "great", user, TODAY);
        service.recomputeAndStore(f.tenantId(), List.of(renterD, renterA), TODAY);

        Page<RenterRatingRowDTO> all = service.list(f.tenantId(), null, null, null, null, PageRequest.of(0, 10));
        assertThat(all.getTotalElements()).isEqualTo(2);

        Page<RenterRatingRowDTO> d = service.list(f.tenantId(), RatingGrade.D, null, null, null, PageRequest.of(0, 10));
        assertThat(d.getTotalElements()).isEqualTo(1);
        RenterRatingRowDTO row = d.getContent().get(0);
        assertThat(row.renterId()).isEqualTo(renterD);
        assertThat(row.score()).isEqualTo(40);
        assertThat(row.currentLeaseId()).isEqualTo(leaseD);
        assertThat(row.propertyName()).isEqualTo(f.propertyName());
        assertThat(row.unitLabel()).isNotBlank();

        Page<RenterRatingRowDTO> q = service.list(f.tenantId(), null, null, "zed ALPHA", null, PageRequest.of(0, 10));
        assertThat(q.getContent()).extracting(RenterRatingRowDTO::renterId).containsExactly(renterA);
        assertThat(q.getContent().get(0).grade()).isEqualTo(RatingGrade.A);

        assertThat(service.list(f.tenantId(), null, f.property().getId(), null, null, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(2);
        assertThat(service.list(f.tenantId(), null, UUID.randomUUID(), null, null, PageRequest.of(0, 10))
                .getTotalElements()).isZero();
        assertThat(service.list(f.tenantId(), null, null, null, List.of(renterD), PageRequest.of(0, 10))
                .getContent()).extracting(RenterRatingRowDTO::renterId).containsExactly(renterD);
        assertThat(service.list(f.tenantId(), null, null, "%", null, PageRequest.of(0, 10))
                .getTotalElements()).isZero();

        Page<RenterRatingRowDTO> sorted = service.list(f.tenantId(), null, null, null, null,
                PageRequest.of(0, 10, org.springframework.data.domain.Sort.by("score").descending()));
        assertThat(sorted.getContent()).extracting(RenterRatingRowDTO::renterId).containsExactly(renterA, renterD);
    }

    @Test
    void listNeverShowsAnotherOrg() {
        LeaseTestFixtures a = newOrgFixtures();
        postedLease(a);
        LeaseTestFixtures b = newOrgFixtures();
        postedLease(b);
        service.recomputeAndStore(a.tenantId(), List.of(a.renter().getId()), TODAY);
        service.recomputeAndStore(b.tenantId(), List.of(b.renter().getId()), TODAY);

        Page<RenterRatingRowDTO> underA = service.list(a.tenantId(), null, null, null,
                List.of(a.renter().getId(), b.renter().getId()), PageRequest.of(0, 10));

        assertThat(underA.getContent()).extracting(RenterRatingRowDTO::renterId).containsExactly(a.renter().getId());
    }
}
