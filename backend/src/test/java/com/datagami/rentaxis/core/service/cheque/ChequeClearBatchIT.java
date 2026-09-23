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
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
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
class ChequeClearBatchIT extends AbstractPostgresIT {

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

    private long penaltyRows() {
        chequeRepo.flush();
        return jdbc.queryForObject("select count(*) from penalty_assessments where tenant_id = ?",
                Long.class, fixtures.tenantId());
    }

    private long crtCount(UUID chequeId) {
        return jdbc.queryForObject(
                "select count(*) from journal_entries where tenant_id = ? and doc_type = ? and source_id = ?",
                Long.class, fixtures.tenantId(), JournalDocType.CRT.name(), chequeId);
    }

    @Test
    void aBatchPostsExactlyWhatTheSameSingleClearsWould() {
        List<UUID> ids = depositedGrid();

        // The reference: N single clears, measured, then rolled back.
        record Measured(List<String> footprints, long penalties) {}
        Measured single = tx.execute(s -> {
            List<String> f = ids.stream()
                    .map(id -> footprint(service.clear(id, new ChequeActionRequest(CLEAR_DATE, "Bank credit 8 Jul", null, null))))
                    .toList();
            long p = penaltyRows();
            s.setRollbackOnly();
            return new Measured(f, p);
        });
        assertThat(ids).allSatisfy(id -> assertThat(crtCount(id)).isZero());

        List<ChequeDTO> batch = service.clearBatch(new ClearBatchRequest(ids, CLEAR_DATE, "Bank credit 8 Jul"));

        assertThat(batch).hasSize(ids.size());
        Measured batched = tx.execute(s -> new Measured(batch.stream().map(this::footprint).toList(), penaltyRows()));
        assertThat(batched.footprints()).containsExactlyElementsOf(single.footprints());
        assertThat(batched.penalties()).isEqualTo(single.penalties());
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
}
