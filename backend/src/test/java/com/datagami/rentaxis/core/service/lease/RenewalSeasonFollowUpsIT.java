package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest;
import com.datagami.rentaxis.api.dto.lease.LeaseBatch;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.renewal.RenewalMarkService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The PR #421 rulings (2026-10-17): the soft "not renewing" mark, and bulk cheque numbering of
 * renewal drafts.
 *
 * <p>The mark gives no notice: the lease's status and notice fields, the unit and its listing are
 * exactly as before. The numbering's parity test saves one twin's grid through the bulk save and
 * the other's through the single grid save with the rows the grid editor sends; the grids, and
 * once posted the journals, must be equal.</p>
 */
@org.springframework.context.annotation.Import(com.datagami.rentaxis.testsupport.LaterBusinessDayConfig.class)
@SpringBootTest
class RenewalSeasonFollowUpsIT extends AbstractPostgresIT {

    @Autowired RenewalMarkService marks;
    @Autowired LeaseBatchService batch;
    @Autowired LeaseRenewalService renewal;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired UnitRepository unitRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;
    @Autowired TransactionTemplate tx;
    @Autowired com.datagami.rentaxis.api.RenterRenewalController renterRenewals;
    @Autowired com.datagami.rentaxis.core.service.renewal.LeaseRenewalScheduler renewalScheduler;
    @Autowired com.datagami.rentaxis.core.service.TenantFeatureService features;
    @Autowired LeaseTransferService transfers;
    @Autowired LeaseTerminationService termination;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures f;
    private int unitSeq = 300;
    private UUID admin;

    private static final LocalDate CONTRACT = LocalDate.of(2026, 12, 15);
    private static final LocalDate START = LocalDate.of(2027, 1, 1);
    private static final LocalDate END = LocalDate.of(2027, 12, 31);

    @BeforeEach
    void setUp() {
        f = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService, accountService,
                propertyAccountService, chargeTypeService).bootstrap().withLeaseServices(leaseService, chequeGeneration, posting);
        admin = actAs(UserRole.TENANT_ADMIN, null, "Amira Admin");
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private UUID posted(Property property) {
        UUID id = f.draftLease(f.createUnit(property, String.valueOf(unitSeq++)), f.createRenter("R " + unitSeq), CONTRACT,
                START, END, List.of(line("RENT", "80000"), line("SECURITY_DEPOSIT", "5000")));
        f.generateGrid(id, 4, START);
        posting.post(id);
        return id;
    }

    private UUID posted() {
        return posted(f.property());
    }

    private String leaseState(UUID id) {
        return jdbc.queryForObject("select status || '|' || coalesce(notice_date::text, '-') || '|' || coalesce(intended_move_out_date::text, '-')"
                + " || '|' || coalesce(notice_given_by, '-') from leases where id = ?", String.class, id);
    }

    private String unitState(UUID leaseId) {
        return jdbc.queryForObject("select u.status || '|' || coalesce(u.current_tenant_name, '-') || '|' || u.version"
                + " from units u join leases l on l.unit_id = u.id where l.id = ?", String.class, leaseId);
    }

    private List<UUID> queue(LeaseService.RenewalMark mark) {
        return tx.execute(s -> leaseService.getAllLeasesPaged(null, null, null, null, List.of(LeaseStatus.DRAFT),
                new LeaseService.ExpiryFilter(END.withDayOfMonth(1), END, null, LeaseService.SuccessorState.NONE, mark),
                PageRequest.of(0, 50))).getContent().stream().map(LeaseDTO::getId).toList();
    }

    // ------------------------------------------------------------------ the soft mark

    @Test
    void markingNotRenewingIsANoteThatGivesNoNoticeAndUndoRemovesIt() {
        UUID a = posted();
        UUID b = posted();
        String leaseBefore = leaseState(a);
        String unitBefore = unitState(a);
        int listingsBefore = jdbc.queryForObject("select count(*) from unit_listings", Integer.class);

        RenewalMarkService.Mark m = marks.markNotRenewing(a, "  Moving to Abu Dhabi ", admin);
        assertThat(m.notRenewing()).isTrue();
        assertThat(m.by()).isEqualTo(admin);
        assertThat(m.at()).isNotNull();

        // No notice, no status change, nothing on the unit or the marketplace.
        assertThat(leaseState(a)).isEqualTo(leaseBefore).startsWith("ACTIVE|-|-|-");
        assertThat(unitState(a)).isEqualTo(unitBefore);
        assertThat(jdbc.queryForObject("select count(*) from unit_listings", Integer.class)).isEqualTo(listingsBefore);
        assertThat(jdbc.queryForObject("select count(*) from journal_entries where lease_id = ? and doc_type <> 'TCO'"
                + " and doc_type <> 'PDR'", Integer.class, a)).isZero();

        // The queue's "not renewing" stage reads the mark; "to do" no longer lists it.
        assertThat(queue(LeaseService.RenewalMark.NOT_RENEWING)).containsExactly(a);
        assertThat(queue(LeaseService.RenewalMark.UNMARKED)).containsExactly(b);
        LeaseDTO row = tx.execute(s -> leaseService.getLeaseById(a));
        assertThat(row.getNotRenewingByName()).isEqualTo("Amira Admin");
        assertThat(row.getNotRenewingNote()).isEqualTo("Moving to Abu Dhabi");
        assertThat(row.getNotRenewingAt()).isNotNull();
        assertThat(jdbc.queryForList("select notes from lease_events where lease_id = ?", String.class, a))
                .anyMatch(n -> n != null && n.contains("Marked not renewing"));

        marks.undo(a);
        assertThat(queue(LeaseService.RenewalMark.NOT_RENEWING)).isEmpty();
        assertThat(queue(LeaseService.RenewalMark.UNMARKED)).containsExactlyInAnyOrder(a, b);
        assertThat(tx.execute(s -> leaseService.getLeaseById(a)).getNotRenewingAt()).isNull();
        assertThat(leaseState(a)).isEqualTo(leaseBefore);
        // Undo of an unmarked contract is harmless.
        assertThat(marks.undo(b).notRenewing()).isFalse();
    }

    private int opportunities(UUID leaseId) {
        return jdbc.queryForObject("select count(*) from renewal_opportunities where lease_id = ?", Integer.class, leaseId);
    }

    private int reminders(UUID leaseId) {
        return jdbc.queryForObject("select count(*) from lease_reminders r join renewal_opportunities o on o.id = r.opportunity_id"
                + " where o.lease_id = ?", Integer.class, leaseId);
    }

    private String renterSees(UUID leaseId) throws Exception {
        UUID renterUser = jdbc.queryForObject("select r.user_id from leases l join renters r on r.id = l.renter_id where l.id = ?",
                UUID.class, leaseId);
        var before = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                renterUser.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_RENTER"))));
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .writeValueAsString(renterRenewals.summary(renterUser.toString()));
        } finally {
            SecurityContextHolder.getContext().setAuthentication(before);
        }
    }

    @Test
    void theMarkIsInvisibleToTheRenterAndOpensNoRenewalOpportunity() throws Exception {
        // Re-review P2-1: a contract ending in 14 months, marked by the landlord.
        UUID a = posted();
        String portalBefore = renterSees(a);
        int funnelBefore = jdbc.queryForObject("select count(*) from renewal_opportunities where tenant_id = ?", Integer.class, f.tenantId());
        marks.markNotRenewing(a, "Owner selling the unit", admin);
        assertThat(opportunities(a)).isZero();
        assertThat(renterSees(a)).isEqualTo(portalBefore).doesNotContain("Owner selling");
        // Round-2 review P2: no LeaseDTO a renter can be handed carries the mark — the renter's own
        // contract list and a single contract read as the renter.
        UUID renterUser = jdbc.queryForObject("select r.user_id from leases l join renters r on r.id = l.renter_id where l.id = ?",
                UUID.class, a);
        var staffAuth = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                renterUser.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_RENTER"))));
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            String mine = json.writeValueAsString(tx.execute(st -> leaseService.getLeasesForRenterUser(renterUser)));
            assertThat(mine).contains(a.toString());
            String one = json.writeValueAsString(tx.execute(st -> leaseService.getLeaseById(a)));
            for (String body : List.of(mine, one)) {
                assertThat(body).doesNotContain("notRenewing").doesNotContain("Owner selling").doesNotContain("Amira Admin");
            }
        } finally {
            SecurityContextHolder.getContext().setAuthentication(staffAuth);
        }
        assertThat(tx.execute(st -> leaseService.getLeaseById(a)).getNotRenewingNote()).isEqualTo("Owner selling the unit");

        marks.undo(a);
        assertThat(renterSees(a)).isEqualTo(portalBefore);
        assertThat(jdbc.queryForObject("select count(*) from renewal_opportunities where tenant_id = ?", Integer.class, f.tenantId()))
                .isEqualTo(funnelBefore);
    }

    @Test
    void theSchedulerSendsNothingBecauseOfAMarkOnAContractOnNoticeEndingSoon() {
        // Re-review P2-1: notice served, 20 days to go — the scheduler never reminds such a renter,
        // and marking the contract must not change that.
        features.setEnabled(f.tenantId(), com.datagami.rentaxis.domain.entity.enums.TenantFeature.LEASE_RENEWALS, true);
        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(20);
        LocalDate start = end.plusDays(1).minusYears(1);
        UUID marked = noticeLease("N-M", start, end);
        UUID control = noticeLease("N-C", start, end);
        marks.markNotRenewing(marked, "Leaving anyway", admin);

        renewalScheduler.runNowForTenant(f.tenantId(), today);
        TenantContextHolder.setTenantId(f.tenantId());

        assertThat(opportunities(marked)).isEqualTo(opportunities(control)).isZero();
        assertThat(reminders(marked)).isEqualTo(reminders(control)).isZero();
        // Neither channel: no reminder e-mail queued for the organisation (the reminder ledger above covers in-app).
        assertThat(jdbc.queryForObject("select count(*) from email_outbox where tenant_id = ? and event_type = 'LEASE_RENEWAL_REMINDER'",
                Integer.class, f.tenantId())).isZero();
    }

    private UUID noticeLease(String unit, LocalDate start, LocalDate end) {
        UUID id = f.draftLease(f.createUnit(f.property(), unit), f.createRenter("R " + unit), start.minusDays(10), start, end,
                List.of(line("RENT", "60000")));
        f.generateGrid(id, 4, start);
        posting.post(id);
        leaseService.giveNotice(id, "Leaving", admin);
        return id;
    }

    @Test
    void extendingTransferringAndTerminatingClearTheMark() {
        // Extend: a further month on the same contract.
        UUID ext = posted();
        marks.markNotRenewing(ext, null, admin);
        LocalDate newEnd = END.plusMonths(1);
        renewalService().extend(ext, new com.datagami.rentaxis.api.dto.lease.ExtendLeaseRequest(newEnd, LocalDate.of(2027, 12, 1),
                List.of(line("RENT", "6000")), List.of(new ChequeRowInput(null, 1, LocalDate.of(2027, 12, 1), LeaseTestFixtures.nextChequeNumber(),
                        END.plusDays(1), "ENBD", null, null, new BigDecimal("6000"), null, com.datagami.rentaxis.domain.entity.enums.ChequeMode.PDC))));
        assertThat(marked(ext)).isFalse();

        // Terminate.
        UUID term = posted();
        marks.markNotRenewing(term, null, admin);
        termination.terminate(term, new com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest(LocalDate.of(2027, 6, 30), null, null, null),
                admin);
        assertThat(marked(term)).isFalse();
        assertThat(queue(LeaseService.RenewalMark.NOT_RENEWING)).doesNotContain(term);

        // Transfer: drafting the move to another unit continues the tenancy.
        UUID move = posted();
        marks.markNotRenewing(move, null, admin);
        transfers.draft(move, new com.datagami.rentaxis.api.dto.lease.TransferLeaseRequest(LocalDate.of(2027, 7, 1),
                f.createUnit(f.property(), "T-" + unitSeq++).getId(), null, LocalDate.of(2027, 6, 15), null, null), posting);
        assertThat(marked(move)).isFalse();
    }

    private LeaseRenewalService renewalService() {
        return renewal;
    }

    private boolean marked(UUID leaseId) {
        return jdbc.queryForObject("select not_renewing_at is not null from leases where id = ?", Boolean.class, leaseId);
    }

    @Test
    void aContractOnNoticeReadsAsNotRenewingWithoutAMark() {
        // Notice cannot be dated before the contract or after today, so this contract started in the past.
        LocalDate start = LocalDate.now().minusMonths(6).withDayOfMonth(1);
        LocalDate end = start.plusYears(1).minusDays(1);
        UUID a = f.draftLease(f.createUnit(f.property(), "N-1"), f.createRenter("On notice"), start.minusDays(10), start, end,
                List.of(line("RENT", "60000")));
        f.generateGrid(a, 4, start);
        posting.post(a);
        leaseService.giveNotice(a, "Leaving", admin);
        List<UUID> notRenewing = tx.execute(s -> leaseService.getAllLeasesPaged(null, null, null, null, null,
                new LeaseService.ExpiryFilter(end, end, null, LeaseService.SuccessorState.NONE, LeaseService.RenewalMark.NOT_RENEWING),
                PageRequest.of(0, 10))).getContent().stream().map(LeaseDTO::getId).toList();
        assertThat(notRenewing).containsExactly(a);
    }

    @Test
    void renewingClearsTheMarkAndAContractWithARenewalCannotBeMarked() {
        UUID a = posted();
        marks.markNotRenewing(a, null, admin);
        renewal.renew(a, new RenewLeaseRequest(LocalDate.of(2027, 12, 15), END.plusDays(1), END.plusYears(1), null, true));
        assertThat(jdbc.queryForObject("select count(*) from leases where id = ? and not_renewing_at is not null",
                Integer.class, a)).isZero();
        assertThatThrownBy(() -> marks.markNotRenewing(a, null, admin)).hasMessageContaining("already has a renewal");
    }

    @Test
    void aPropertyManagerMarksOnlyTheirOwnBuildingsContracts() {
        UUID mine = posted(f.property());
        UUID other = posted(f.createProperty("OTH"));
        UUID pm = actAs(UserRole.PROPERTY_MANAGER, f.property().getId(), "Paul PM");
        marks.markNotRenewing(mine, null, pm);
        assertThatThrownBy(() -> marks.markNotRenewing(other, null, pm)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> marks.undo(other)).isInstanceOf(NotFoundException.class);
        assertThat(queue(LeaseService.RenewalMark.NOT_RENEWING)).containsExactly(mine);
    }

    @Test
    void anotherOrganisationCannotSeeSetOrUndoTheMark() {
        UUID ours = posted();
        UUID ourTenant = f.tenantId();
        marks.markNotRenewing(ours, "ours", admin);

        LeaseTestFixtures other = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService,
                accountService, propertyAccountService, chargeTypeService).bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
        UUID theirs = other.postedLease(CONTRACT, START, END, List.of(line("RENT", "60000")), 4, null).lease().getId();
        UUID theirAdmin = UUID.randomUUID();
        assertThatThrownBy(() -> marks.markNotRenewing(ours, null, theirAdmin)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> marks.undo(ours)).isInstanceOf(NotFoundException.class);
        assertThat(tx.execute(s -> leaseService.getAllLeasesPaged(null, null, null, null, null,
                new LeaseService.ExpiryFilter(null, null, null, LeaseService.SuccessorState.ANY, LeaseService.RenewalMark.NOT_RENEWING),
                PageRequest.of(0, 50))).getContent()).isEmpty();

        TenantContextHolder.setTenantId(ourTenant);
        LeaseTestFixtures.authenticateAsTenantAdmin();
        assertThatThrownBy(() -> marks.markNotRenewing(theirs, null, admin)).isInstanceOf(NotFoundException.class);
        assertThat(jdbc.queryForObject("select not_renewing_note from leases where id = ?", String.class, ours))
                .isEqualTo("ours");
    }

    // ------------------------------------------------------------------ bulk cheque numbering

    /** A renewal draft with a generated, unnumbered grid of four rent rows. */
    private UUID renewalDraft() {
        UUID pred = posted();
        UUID draft = renewal.renew(pred, new RenewLeaseRequest(LocalDate.of(2027, 12, 15), END.plusDays(1), END.plusYears(1),
                null, true)).getId();
        chequeGeneration.generate(draft, new GenerateChequesRequest(4, null, null, null, null, null, null));
        return draft;
    }

    private List<ChequeDTO> grid(UUID draft) {
        return tx.execute(s -> chequeGeneration.list(draft));
    }

    private Long version(UUID draft) {
        return jdbc.queryForObject("select version from leases where id = ?", Long.class, draft);
    }

    private List<LeaseBatch.ChequeNumberRow> numbered(UUID draft, String from, String bank) {
        List<LeaseBatch.ChequeNumberRow> rows = new ArrayList<>();
        int n = Integer.parseInt(from);
        for (ChequeDTO c : grid(draft)) {
            rows.add(new LeaseBatch.ChequeNumberRow(c.id(), String.valueOf(n++), bank, c.chequeDate().plusDays(1), c.amount()));
        }
        return rows;
    }

    /** What the grid editor sends ({@code ChequeGrid.toChequeRows}) after the same edits. */
    private List<ChequeRowInput> asTheGridSends(UUID draft, List<LeaseBatch.ChequeNumberRow> edits) {
        Map<UUID, LeaseBatch.ChequeNumberRow> byId = new java.util.HashMap<>();
        edits.forEach(e -> byId.put(e.chequeId(), e));
        List<ChequeRowInput> out = new ArrayList<>();
        for (ChequeDTO c : grid(draft)) {
            LeaseBatch.ChequeNumberRow e = byId.get(c.id());
            out.add(new ChequeRowInput(c.id(), c.seqNo(), c.postingDate(), e != null ? e.chequeNumber() : c.chequeNumber(),
                    e != null ? e.chequeDate() : c.chequeDate(), e != null ? e.payeeBank() : c.payeeBank(), c.payerName(),
                    c.debitAccountId(), c.amount(), c.narration(), c.mode(), c.vatAmount(), c.rowKind()));
        }
        return out;
    }

    private List<String> gridShape(UUID draft) {
        return jdbc.query("select seq_no, amount, cheque_date, coalesce(cheque_number, '-'), coalesce(payee_bank, '-'), status,"
                        + " coalesce(vat_amount::text, '-'), coalesce(row_kind, '-'), mode from cheques where lease_id = ? order by seq_no",
                (rs, i) -> String.join("|", rs.getString(1), rs.getBigDecimal(2).toPlainString(), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)), draft);
    }

    private List<String> journals(UUID leaseId) {
        return jdbc.query("""
                select e.doc_type, e.entry_date, a.code, l.debit, l.credit
                from journal_lines l join journal_entries e on e.id = l.journal_entry_id join accounts a on a.id = l.account_id
                where e.lease_id = ? order by 1, 2, 3, 4, 5""", (rs, i) -> String.join("|", rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getBigDecimal(4).toPlainString(), rs.getBigDecimal(5).toPlainString()), leaseId);
    }

    @Test
    void aBulkSaveIsTheSingleGridSaveDraftByDraftAndPostsTheSameJournals() {
        UUID a = renewalDraft();
        UUID b = renewalDraft();
        List<LeaseBatch.ChequeNumberRow> editsA = numbered(a, "700001", "Emirates NBD");
        List<LeaseBatch.ChequeNumberRow> editsB = numbered(b, "700001", "Emirates NBD");
        int journalsBefore = jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ?", Integer.class, f.tenantId());

        List<LeaseBatch.DraftGrid> loaded = batch.draftGrids(new LeaseBatch.DraftsRequest(List.of(a)));
        assertThat(loaded.get(0).ok()).as(loaded.get(0).message()).isTrue();
        assertThat(loaded.get(0).cheques()).hasSize(grid(a).size());
        List<LeaseBatch.DraftNumbersRow> rows = batch.saveChequeNumbers(new LeaseBatch.ChequeNumbersRequest(
                List.of(new LeaseBatch.DraftNumbers(a, loaded.get(0).version(), editsA))));
        assertThat(rows.get(0).ok()).as(rows.get(0).message() + " " + rows.get(0).rowErrors()).isTrue();

        chequeGeneration.saveRows(b, asTheGridSends(b, editsB), version(b));

        assertThat(gridShape(a)).isEqualTo(gridShape(b));
        assertThat(gridShape(a)).allMatch(r -> r.contains("|Emirates NBD|"));
        assertThat(version(a)).isEqualTo(version(b));
        // Drafts only: no journal yet.
        assertThat(jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ?", Integer.class, f.tenantId()))
                .isEqualTo(journalsBefore);

        posting.post(a);
        posting.post(b);
        assertThat(journals(a)).isNotEmpty().isEqualTo(journals(b));
    }

    @Test
    void aDraftWithBadRowsIsRefusedRowByRowAndTheOthersAreSaved() {
        UUID good = renewalDraft();
        UUID bad = renewalDraft();
        List<String> badBefore = gridShape(bad);
        List<LeaseBatch.ChequeNumberRow> edits = new ArrayList<>(numbered(bad, "800001", "ADCB"));
        LeaseBatch.ChequeNumberRow first = edits.get(0);
        LeaseBatch.ChequeNumberRow second = edits.get(1);
        edits.set(1, new LeaseBatch.ChequeNumberRow(second.chequeId(), first.chequeNumber(), "ADCB", second.chequeDate(), second.amount()));
        LeaseBatch.ChequeNumberRow third = edits.get(2);
        edits.set(2, new LeaseBatch.ChequeNumberRow(third.chequeId(), " ", "ADCB", third.chequeDate(), third.amount()));
        LeaseBatch.ChequeNumberRow fourth = edits.get(3);
        edits.set(3, new LeaseBatch.ChequeNumberRow(fourth.chequeId(), "800009", "ADCB", fourth.chequeDate(),
                fourth.amount().add(BigDecimal.ONE)));

        List<LeaseBatch.DraftNumbersRow> rows = batch.saveChequeNumbers(new LeaseBatch.ChequeNumbersRequest(List.of(
                new LeaseBatch.DraftNumbers(bad, version(bad), edits),
                new LeaseBatch.DraftNumbers(good, version(good), numbered(good, "900001", "ADCB")))));
        assertThat(rows).extracting(LeaseBatch.DraftNumbersRow::ok).containsExactly(false, true);
        assertThat(rows.get(0).rowErrors()).extracting(LeaseBatch.RowError::code)
                .containsExactlyInAnyOrder("cheque.numberRepeated", "cheque.numberRequired", "cheque.amountChanged");
        assertThat(rows.get(0).rowErrors()).extracting(LeaseBatch.RowError::chequeId)
                .containsExactlyInAnyOrder(second.chequeId(), third.chequeId(), fourth.chequeId());
        assertThat(gridShape(bad)).isEqualTo(badBefore);
        assertThat(gridShape(good)).allMatch(r -> r.contains("|ADCB|"));
    }

    @Test
    void aChequeOfAnotherContractSentInADraftsRowsIsRefusedAndNothingIsWritten() {
        UUID mine = renewalDraft();
        UUID other = renewalDraft();
        List<String> before = gridShape(mine);
        List<LeaseBatch.ChequeNumberRow> rows = new ArrayList<>(numbered(mine, "450001", "FAB"));
        ChequeDTO foreign = grid(other).get(0);
        rows.add(new LeaseBatch.ChequeNumberRow(foreign.id(), "450099", "FAB", foreign.chequeDate(), foreign.amount()));
        LeaseBatch.DraftNumbersRow r = batch.saveChequeNumbers(new LeaseBatch.ChequeNumbersRequest(List.of(
                new LeaseBatch.DraftNumbers(mine, version(mine), rows)))).get(0);
        assertThat(r.ok()).isFalse();
        assertThat(r.rowErrors()).extracting(LeaseBatch.RowError::code).containsExactly("cheque.notInDraft");
        assertThat(gridShape(mine)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select cheque_number from cheques where id = ?", String.class, foreign.id())).isNull();
    }

    @Test
    void arabicIndicDigitsAreTheSameNumberAndAreSavedAsAscii() {
        UUID draft = renewalDraft();
        List<LeaseBatch.ChequeNumberRow> rows = new ArrayList<>(numbered(draft, "510001", "FAB"));
        LeaseBatch.ChequeNumberRow a = rows.get(0);
        LeaseBatch.ChequeNumberRow b = rows.get(1);
        rows.set(0, new LeaseBatch.ChequeNumberRow(a.chequeId(), "\u0665\u0661\u0660\u0660\u0660\u0661", "FAB", a.chequeDate(), a.amount()));
        rows.set(1, new LeaseBatch.ChequeNumberRow(b.chequeId(), "510001", "FAB", b.chequeDate(), b.amount()));
        LeaseBatch.DraftNumbersRow refused = batch.saveChequeNumbers(new LeaseBatch.ChequeNumbersRequest(List.of(
                new LeaseBatch.DraftNumbers(draft, version(draft), rows)))).get(0);
        assertThat(refused.rowErrors()).extracting(LeaseBatch.RowError::code).containsExactly("cheque.numberRepeated");
        assertThat(refused.args()).containsEntry("count", 1);

        rows.set(1, new LeaseBatch.ChequeNumberRow(b.chequeId(), "\u06F5\u06F1\u06F0\u06F0\u06F0\u06F2", "FAB", b.chequeDate(), b.amount()));
        assertThat(batch.saveChequeNumbers(new LeaseBatch.ChequeNumbersRequest(List.of(
                new LeaseBatch.DraftNumbers(draft, version(draft), rows)))).get(0).ok()).isTrue();
        assertThat(jdbc.queryForList("select cheque_number from cheques where lease_id = ? order by seq_no", String.class, draft))
                .startsWith("510001", "510002");
    }

    @Test
    void aDraftChangedSinceItWasLoadedIsRefusedAndOnlyDraftsAreNumbered() {
        UUID draft = renewalDraft();
        Long loadedVersion = version(draft);
        // Somebody else saved the draft meanwhile.
        jdbc.update("update leases set version = version + 1 where id = ?", draft);
        List<LeaseBatch.DraftNumbersRow> rows = batch.saveChequeNumbers(new LeaseBatch.ChequeNumbersRequest(List.of(
                new LeaseBatch.DraftNumbers(draft, loadedVersion, numbered(draft, "600001", "FAB")))));
        assertThat(rows.get(0).ok()).isFalse();
        assertThat(rows.get(0).code()).isEqualTo("lease.changed");

        UUID active = posted();
        assertThat(batch.draftGrids(new LeaseBatch.DraftsRequest(List.of(active))).get(0).code()).isEqualTo("lease.notDraft");
        assertThat(batch.saveChequeNumbers(new LeaseBatch.ChequeNumbersRequest(List.of(
                new LeaseBatch.DraftNumbers(active, version(active), List.of())))).get(0).code()).isEqualTo("lease.notDraft");
    }

    @Test
    void numberingIsScopedToTheManagersBuildingsAndTheOrganisation() {
        UUID draft = renewalDraft();
        UUID pm = actAs(UserRole.PROPERTY_MANAGER, f.createProperty("ELSE").getId(), "Out Of Scope");
        assertThat(pm).isNotNull();
        assertThat(batch.draftGrids(new LeaseBatch.DraftsRequest(List.of(draft))).get(0).code()).isEqualTo("lease.notFound");
        assertThat(batch.saveChequeNumbers(new LeaseBatch.ChequeNumbersRequest(List.of(
                new LeaseBatch.DraftNumbers(draft, version(draft), List.of())))).get(0).code()).isEqualTo("lease.notFound");

        new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService, accountService,
                propertyAccountService, chargeTypeService).bootstrap();
        assertThat(batch.draftGrids(new LeaseBatch.DraftsRequest(List.of(draft))).get(0).code()).isEqualTo("lease.notFound");
        assertThat(jdbc.queryForObject("select count(*) from cheques where lease_id = ? and cheque_number is not null",
                Integer.class, draft)).isZero();
    }

    private UUID actAs(UserRole role, UUID propertyId, String name) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@t.io");
        u.setName(name);
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(f.tenantId());
        UUID id = userRepo.save(u).getId();
        if (propertyId != null) {
            UserPropertyAssignment a = new UserPropertyAssignment();
            a.setUserId(id);
            a.setPropertyId(propertyId);
            assignmentRepo.save(a);
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                id.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
        return id;
    }

}
