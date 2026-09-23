package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.cheque.ClearBatchRequest;
import com.datagami.rentaxis.api.dto.cheque.DepositBatchRequest;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.LandlordOrgFineSettings;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.PenaltyType;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgFineSettingsRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gap #57: the bank clears a deposit slip as one credit, so the register clears
 * a batch in one act — the mirror of {@code depositBatch}: all or nothing, each
 * row must be DEPOSITED, the refusal names the row, and every row posts exactly
 * what a single {@code clear} posts.
 */
@SpringBootTest
@Import(ChequeClearBatchIT.FixedClockConfig.class)
class ChequeClearBatchIT extends AbstractPostgresIT {

    /**
     * The app clock's today. The fixture's dates are in 2027, and a clearing date
     * may not be in the future (review M2), so the clock is pinned after them.
     */
    static final LocalDate TODAY = LocalDate.of(2027, 7, 31);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            ZoneId dubai = ZoneId.of("Asia/Dubai");
            return Clock.fixed(TODAY.atTime(10, 0).atZone(dubai).toInstant(), dubai);
        }
    }

    @Autowired ChequeService service;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService generation;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired ChequeRepository chequeRepo;
    @Autowired JournalEntryRepository entries;
    @Autowired JournalLineRepository lines;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgFineSettingsRepository fineSettingsRepo;
    @Autowired RentCollectionSettingsRepository rentCollectionSettingsRepo;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;
    @Autowired AccountResolver resolver;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);
    /** After the last row of the grid falls due, so the whole grid may be banked. */
    private static final LocalDate DEPOSIT_DATE = LocalDate.of(2027, 7, 5);
    private static final LocalDate CLEAR_DATE = LocalDate.of(2027, 7, 8);

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

    /** 53,000 over five numbered instruments (100040 up), all banked. */
    private List<UUID> depositedGrid() {
        PostLeaseResponse r = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, "100040");
        List<UUID> ids = r.cheques().stream().map(ChequeDTO::id).toList();
        service.depositBatch(new DepositBatchRequest(ids, DEPOSIT_DATE, null));
        return ids;
    }

    /**
     * Everything a clearance leaves behind that is not an id or a sequence number:
     * the row's new state, and its CRT entry line by line with every dimension.
     */
    private String footprint(ChequeDTO cleared) {
        StringBuilder out = new StringBuilder()
                .append(cleared.id()).append(' ').append(cleared.status())
                .append(" cleared=").append(cleared.clearedAt())
                .append(" debit=").append(cleared.debitAccountId());
        JournalEntry e = entries.findById(cleared.crtJournalId()).orElseThrow();
        out.append(" | ").append(e.getDocType()).append(' ').append(e.getEntryDate())
                .append(' ').append(e.getSourceType()).append(' ').append(e.getSourceId());
        for (JournalLine l : lines.findByEntry_IdOrderByLineNoAsc(e.getId())) {
            out.append(" | ").append(l.getLineNo()).append(' ').append(l.getAccountId())
                    .append(" dr=").append(l.getDebit().stripTrailingZeros().toPlainString())
                    .append(" cr=").append(l.getCredit().stripTrailingZeros().toPlainString())
                    .append(" contra=").append(l.getContraAccountId())
                    .append(' ').append(l.getChequeId()).append(' ').append(l.getLeaseId())
                    .append(' ').append(l.getPropertyId()).append(' ').append(l.getUnitId())
                    .append(' ').append(l.getRenterId()).append(' ').append(l.getNarration());
        }
        return out.toString();
    }

    /** Every penalty proposal of the tenant, one line each, in cheque order. */
    private List<String> penalties() {
        chequeRepo.flush();
        return jdbc.queryForList("""
                select cheque_id::text || ' ' || reason || ' ' || amount || ' ' || status || ' ' || coalesce(description, '')
                from penalty_assessments where tenant_id = ? order by cheque_id""",
                String.class, fixtures.tenantId());
    }

    /**
     * Late clearing proposes a fine: the organisation's auto-propose flag on, and a
     * FIXED_PER_DAY rate on the property. Without this the batch-vs-single penalty
     * comparison was 0 against 0 and could not fail (review M3).
     */
    private void lateFeesOn() {
        tx.executeWithoutResult(st -> {
            LandlordOrgFineSettings o = fineSettingsRepo.findByLandlordOrgId(fixtures.tenantId())
                    .orElseGet(LandlordOrgFineSettings::new);
            o.setLandlordOrgId(fixtures.tenantId());
            o.setTenantId(fixtures.tenantId());
            o.setFineBounceAmount(new BigDecimal("500"));
            o.setFineSignatureMismatchAmount(new BigDecimal("500"));
            o.setFineAccountClosedAmount(new BigDecimal("1000"));
            o.setFineGraceDays(7);
            o.setFinePerDayRate(new BigDecimal("25"));
            o.setBouncesBeforePenalty(2);
            o.setAutoProposeChequeReturn(true);
            o.setAutoProposeLatePayment(true);
            fineSettingsRepo.save(o);

            RentCollectionSettings rcs = rentCollectionSettingsRepo.findByPropertyId(fixtures.property().getId())
                    .orElseGet(() -> {
                        RentCollectionSettings n = new RentCollectionSettings();
                        n.setTenantId(fixtures.tenantId());
                        n.setProperty(fixtures.property());
                        return n;
                    });
            rcs.setPenaltyType(PenaltyType.FIXED_PER_DAY);
            rcs.setPenaltyAmount(new BigDecimal("50"));
            rcs.setGracePeriodDays(5);
            rentCollectionSettingsRepo.save(rcs);
        });
    }

    private void assertNothingCleared(List<UUID> ids) {
        for (UUID id : ids) {
            Cheque c = tx.execute(s -> chequeRepo.findById(id).orElseThrow());
            assertThat(c.getStatus()).isEqualTo(ChequeStatus.DEPOSITED);
            assertThat(crtCount(id)).isZero();
        }
    }

    private long crtCount(UUID chequeId) {
        return jdbc.queryForObject(
                "select count(*) from journal_entries where tenant_id = ? and doc_type = ? and source_id = ?",
                Long.class, fixtures.tenantId(), JournalDocType.CRT.name(), chequeId);
    }

    @Test
    void aBatchPostsExactlyWhatTheSameSingleClearsWould() {
        lateFeesOn();
        List<UUID> ids = depositedGrid();

        // The reference: N single clears, measured, then rolled back.
        record Measured(List<String> footprints, List<String> penalties) {}
        Measured single = tx.execute(s -> {
            List<String> f = ids.stream()
                    .map(id -> footprint(service.clear(id, new ChequeActionRequest(CLEAR_DATE, "Bank credit 8 Jul", null, null))))
                    .toList();
            List<String> p = penalties();
            s.setRollbackOnly();
            return new Measured(f, p);
        });
        assertThat(ids).allSatisfy(id -> assertThat(crtCount(id)).isZero());
        assertThat(penalties()).isEmpty();
        // The rows fall due from Oct 2026 and clear in Jul 2027: most are late, so
        // the comparison below is between real proposals, not 0 and 0.
        assertThat(single.penalties()).hasSizeGreaterThanOrEqualTo(2);

        List<ChequeDTO> batch = service.clearBatch(new ClearBatchRequest(ids, CLEAR_DATE, "Bank credit 8 Jul"));

        assertThat(batch).hasSize(ids.size());
        Measured batched = tx.execute(s -> new Measured(batch.stream().map(this::footprint).toList(), penalties()));
        assertThat(batched.footprints()).containsExactlyElementsOf(single.footprints());
        assertThat(batched.penalties()).containsExactlyElementsOf(single.penalties());
        assertThat(ids).allSatisfy(id -> {
            assertThat(crtCount(id)).isEqualTo(1);
            Cheque c = tx.execute(s -> chequeRepo.findById(id).orElseThrow());
            assertThat(c.getStatus()).isEqualTo(ChequeStatus.CLEARED);
            assertThat(c.getClearedAt()).isEqualTo(CLEAR_DATE);
        });
    }

    @Test
    void oneRowThatIsNotDepositedRefusesTheWholeBatchByName() {
        List<UUID> ids = depositedGrid();
        service.clear(ids.get(1), ChequeActionRequest.on(CLEAR_DATE));

        assertThatThrownBy(() -> service.clearBatch(new ClearBatchRequest(ids, CLEAR_DATE, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("100041 is CLEARED")
                .hasMessageContaining("nothing was cleared");

        for (int i = 0; i < ids.size(); i++) {
            UUID id = ids.get(i);
            Cheque c = tx.execute(s -> chequeRepo.findById(id).orElseThrow());
            assertThat(c.getStatus()).isEqualTo(i == 1 ? ChequeStatus.CLEARED : ChequeStatus.DEPOSITED);
            assertThat(crtCount(id)).isEqualTo(i == 1 ? 1 : 0);
        }
    }

    @Test
    void anotherTenantsChequeIsRefusedAndNothingClears() {
        List<UUID> ids = depositedGrid();
        UUID tenantA = fixtures.tenantId();

        // As another tenant: tenant A's cheques are not there to clear.
        new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService).bootstrap();
        assertThatThrownBy(() -> service.clearBatch(new ClearBatchRequest(List.of(ids.get(0)), CLEAR_DATE, null)))
                .isInstanceOfAny(BusinessRuleViolationException.class, NotFoundException.class);

        TenantContextHolder.setTenantId(tenantA);
        for (UUID id : ids) {
            Cheque c = tx.execute(s -> chequeRepo.findById(id).orElseThrow());
            assertThat(c.getStatus()).isEqualTo(ChequeStatus.DEPOSITED);
            assertThat(crtCount(id)).isZero();
        }
    }

    @Test
    void anEmptySelectionIsRefused() {
        assertThatThrownBy(() -> service.clearBatch(new ClearBatchRequest(List.of(), CLEAR_DATE, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("at least one cheque");
    }

    // ---- review I2: size cap ----

    @Test
    void moreThanFiveHundredIdsIsRefusedBeforeAnythingIsRead() {
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i < ChequeService.MAX_BATCH + 1; i++) tooMany.add(UUID.randomUUID());
        assertThatThrownBy(() -> service.clearBatch(new ClearBatchRequest(tooMany, CLEAR_DATE, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("at most 500");
        assertThatThrownBy(() -> service.depositBatch(new DepositBatchRequest(tooMany, DEPOSIT_DATE, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("at most 500");
    }

    @Test
    void exactlyFiveHundredIdsIsNotRefusedForItsSize() {
        List<UUID> five = new ArrayList<>();
        for (int i = 0; i < ChequeService.MAX_BATCH; i++) five.add(UUID.randomUUID());
        // Refused, but for the rows (none exists), not for the count.
        assertThatThrownBy(() -> service.clearBatch(new ClearBatchRequest(five, CLEAR_DATE, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("does not exist")
                .hasMessageNotContaining("at most");
        assertThatThrownBy(() -> service.depositBatch(new DepositBatchRequest(five, DEPOSIT_DATE, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("does not exist")
                .hasMessageNotContaining("at most");
    }

    // ---- review M1: a property manager's scope ----

    /** A PROPERTY_MANAGER assigned to a different property of the same tenant. */
    private void asManagerOfAnotherProperty() {
        User pm = new User();
        pm.setEmail("pm-" + UUID.randomUUID() + "@t.io");
        pm.setName("PM");
        pm.setRole(UserRole.PROPERTY_MANAGER);
        pm.setStatus(UserStatus.ACTIVE);
        pm.setPasswordHash("x");
        pm.setTenantId(fixtures.tenantId());
        UUID userId = userRepo.save(pm).getId();
        UserPropertyAssignment a = new UserPropertyAssignment();
        a.setUserId(userId);
        a.setPropertyId(fixtures.createProperty("OTHER").getId());
        assignmentRepo.save(a);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_PROPERTY_MANAGER"))));
    }

    @Test
    void aManagersRefusalDoesNotNameChequesOutsideTheirBuildings() {
        List<UUID> ids = depositedGrid();
        // One row the up-front check would refuse by name ("100041 is CLEARED"),
        // which is how the old message leaked another building's cheque.
        service.clear(ids.get(1), ChequeActionRequest.on(CLEAR_DATE));
        asManagerOfAnotherProperty();

        assertThatThrownBy(() -> service.clearBatch(new ClearBatchRequest(ids, CLEAR_DATE, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining(ids.get(0) + " does not exist")
                .hasMessageNotContaining("10004")
                .hasMessageNotContaining(" is CLEARED");

        fixtures.asTenantAdmin();
        assertNothingCleared(ids.stream().filter(id -> !id.equals(ids.get(1))).toList());
    }

    @Test
    void aManagersDepositRefusalDoesNotNameChequesOutsideTheirBuildingsEither() {
        PostLeaseResponse r = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, "100040");
        List<UUID> ids = r.cheques().stream().map(ChequeDTO::id).toList();
        // As above: a row the up-front check would name ("100041 is DEPOSITED").
        service.deposit(ids.get(1), ChequeActionRequest.on(DEPOSIT_DATE));
        asManagerOfAnotherProperty();

        assertThatThrownBy(() -> service.depositBatch(new DepositBatchRequest(ids, DEPOSIT_DATE, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining(ids.get(0) + " does not exist")
                .hasMessageNotContaining("10004")
                .hasMessageNotContaining(" is DEPOSITED");
    }

    // ---- review M2: the clearing date ----

    @Test
    void aClearingDateBeforeARowsDepositIsRefusedByName() {
        List<UUID> ids = depositedGrid();
        assertThatThrownBy(() -> service.clearBatch(new ClearBatchRequest(ids, DEPOSIT_DATE.minusDays(1), null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("100040 was deposited on " + DEPOSIT_DATE)
                .hasMessageContaining("nothing was cleared");
        assertNothingCleared(ids);
    }

    @Test
    void aClearingDateOnTheDepositDateIsAccepted() {
        List<UUID> ids = depositedGrid();
        List<ChequeDTO> out = service.clearBatch(new ClearBatchRequest(ids, DEPOSIT_DATE, null));
        assertThat(out).allSatisfy(c -> assertThat(c.clearedAt()).isEqualTo(DEPOSIT_DATE));
    }

    @Test
    void aFutureClearingDateIsRefused() {
        List<UUID> ids = depositedGrid();
        assertThatThrownBy(() -> service.clearBatch(new ClearBatchRequest(ids, TODAY.plusDays(1), null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("in the future");
        assertNothingCleared(ids);
    }

    @Test
    void noClearingDateMeansTodayOnTheAppClock() {
        List<UUID> ids = depositedGrid();
        List<ChequeDTO> out = service.clearBatch(new ClearBatchRequest(ids, null, null));
        assertThat(out).allSatisfy(c -> assertThat(c.clearedAt()).isEqualTo(TODAY));
    }

    // ---- review M3: a failure inside the loop ----

    /**
     * Every up-front check passes; the last row then fails inside the loop, after
     * the rows before it have posted their CRTs in this transaction. All of them
     * must roll back.
     */
    @Test
    void aRowThatFailsPartwayThroughRollsBackTheRowsBeforeIt() {
        List<UUID> ids = depositedGrid();
        // The row's own debit account, pointed at a receivable: requireSettlementAccount
        // refuses it only when that row is cleared, i.e. inside the loop.
        UUID receivable = tx.execute(s -> resolver.resolve(AccountRole.RENT_RECEIVABLE, fixtures.property().getId()).getId());
        UUID last = ids.get(ids.size() - 1);
        jdbc.update("update cheques set debit_account_id = ? where id = ?", receivable, last);

        assertThatThrownBy(() -> service.clearBatch(new ClearBatchRequest(ids, CLEAR_DATE, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("must be a bank or cash account");

        assertNothingCleared(ids);
        assertThat(jdbc.queryForObject(
                "select count(*) from journal_entries where tenant_id = ? and doc_type = ?",
                Long.class, fixtures.tenantId(), JournalDocType.CRT.name())).isZero();
    }
}
