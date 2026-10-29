package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseBatch;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest.RentChange;
import com.datagami.rentaxis.api.dto.lease.RentFreePeriodDTO;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
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
import com.datagami.rentaxis.testsupport.LockedBooks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bulk renew and bulk terminate (scale spec #16, #17) against a real database.
 *
 * <p>The parity tests are the point: each batch is run on one set of contracts and the same
 * act is done by hand, one contract at a time through the single-contract service with the
 * request the single screen sends, on an identical twin set (same property, same terms,
 * another unit and renter). The drafts must match line for line, the grids row for row, and
 * once posted (or terminated) the journals entry for entry — doc type, date, account, debit,
 * credit. Anything a batch did differently from the single path would show up here.</p>
 */
@org.springframework.context.annotation.Import(com.datagami.rentaxis.testsupport.LaterBusinessDayConfig.class)
@SpringBootTest
class LeaseBatchServiceIT extends AbstractPostgresIT {

    @Autowired LeaseBatchService batch;
    @Autowired LeaseRenewalService renewal;
    @Autowired LeaseTerminationService termination;
    /** The same bean, spied, so a test can see the transaction a batch preview runs in (review P3-1). */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean LeaseTerminationService terminationSpy;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired RentFreeService rentFree;
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
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures f;
    private int unitSeq = 200;

    private static final LocalDate CONTRACT = LocalDate.of(2026, 12, 15);
    private static final LocalDate START = LocalDate.of(2027, 1, 1);
    private static final LocalDate END = LocalDate.of(2027, 12, 31);
    private static final LocalDate R_CONTRACT = LocalDate.of(2027, 12, 15);
    private static final LocalDate R_START = LocalDate.of(2028, 1, 1);
    private static final LocalDate R_END = LocalDate.of(2028, 12, 31);

    @BeforeEach
    void setUp() {
        f = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService, accountService,
                propertyAccountService, chargeTypeService).bootstrap().withLeaseServices(leaseService, chequeGeneration, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------------ fixtures

    private static List<LeaseLineInput> terms() {
        return List.of(line("RENT", "85000", "5000"), line("ADMIN_FEE", "1500"), line("SECURITY_DEPOSIT", "5000"));
    }

    /** A posted contract on a new unit of {@code property} for a new renter. */
    private UUID posted(Property property, LocalDate start, LocalDate end, boolean januaryRentFree) {
        Unit unit = f.createUnit(property, String.valueOf(unitSeq++));
        Renter renter = f.createRenter("Renter " + unitSeq);
        UUID id = f.draftLease(unit, renter, CONTRACT, start, end, terms());
        if (januaryRentFree) {
            rentFree.replace(id, List.of(new RentFreePeriodDTO(null, start, start.plusMonths(1).minusDays(1), null,
                    "Fit-out", null, null)));
        }
        f.generateGrid(id, 4, start);
        posting.post(id);
        return id;
    }

    private UUID posted(boolean januaryRentFree) {
        return posted(f.property(), START, END, januaryRentFree);
    }

    private static LeaseBatch.RenewRequest renewAll(List<UUID> ids, LeaseBatch.RentRule rule, boolean grid,
                                                    List<LeaseLineInput> extra) {
        return new LeaseBatch.RenewRequest(ids, rule, null, R_CONTRACT, true, grid, extra);
    }

    /** What the single renew dialog sends for "next 12 months" with this rent change. */
    private static RenewLeaseRequest single(RentChange change, List<LeaseLineInput> extra) {
        return new RenewLeaseRequest(R_CONTRACT, R_START, R_END, null, true, change, null, extra);
    }

    private UUID renewalOf(UUID leaseId) {
        return jdbc.queryForObject("select id from leases where renewed_from_lease_id = ?", UUID.class, leaseId);
    }

    /** The draft as the ledger and the contract will see it: header, lines, grid. */
    private List<String> draftShape(UUID draftId) {
        List<String> out = new ArrayList<>(jdbc.query("""
                select status, start_date, end_date, contract_date, renewal_previous_rent, renewal_change_percent,
                       carry_deposit_forward, payment_terms
                from leases where id = ?""", (rs, i) -> String.join("|", rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), String.valueOf(rs.getBigDecimal(5)), String.valueOf(rs.getBigDecimal(6)),
                rs.getString(7), rs.getString(8)), draftId));
        out.addAll(jdbc.query("""
                select ct.code, ll.gross_amount, ll.discount_amount, ll.rent_free_amount, ll.net_amount, ll.vat_applicable,
                       ll.period_start, ll.period_end
                from lease_lines ll join charge_types ct on ct.id = ll.charge_type_id
                where ll.lease_id = ? order by ll.seq_no""", (rs, i) -> "line|" + String.join("|", rs.getString(1),
                rs.getBigDecimal(2).toPlainString(), rs.getBigDecimal(3).toPlainString(), rs.getBigDecimal(4).toPlainString(),
                rs.getBigDecimal(5).toPlainString(), rs.getString(6), rs.getString(7), rs.getString(8)), draftId));
        out.addAll(jdbc.query("select seq_no, amount, cheque_date, row_kind, status from cheques where lease_id = ? order by seq_no",
                (rs, i) -> "cheque|" + String.join("|", rs.getString(1), rs.getBigDecimal(2).toPlainString(),
                        rs.getString(3), rs.getString(4), rs.getString(5)), draftId));
        return out;
    }

    /** Every journal line written for a lease, as doc type, date, account code, debit, credit. */
    private List<String> journals(UUID leaseId) {
        return jdbc.query("""
                select e.doc_type, e.entry_date, a.code, l.debit, l.credit
                from journal_lines l join journal_entries e on e.id = l.journal_entry_id
                join accounts a on a.id = l.account_id
                where e.lease_id = ? order by e.doc_type, e.entry_date, a.code, l.debit, l.credit""",
                (rs, i) -> String.join("|", rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4).toPlainString(), rs.getBigDecimal(5).toPlainString()), leaseId);
    }

    private List<String> chequeStatuses(UUID leaseId) {
        return jdbc.queryForList("select status from cheques where lease_id = ? order by seq_no", String.class, leaseId);
    }

    private void postDraft(UUID draftId) {
        f.numberGrid(draftId, LeaseTestFixtures.nextChequeBook());
        posting.post(draftId);
    }

    private void assertRenewParity(LeaseBatch.RentRule rule, RentChange singleChange, boolean januaryRentFree,
                                   List<LeaseLineInput> extra, String expectedRent) {
        assertRenewParity(rule, singleChange, januaryRentFree, extra, extra, expectedRent);
    }

    /** {@code batchExtra} is what the bulk dialog sends, {@code singleExtra} what the single renew dialog sends. */
    private void assertRenewParity(LeaseBatch.RentRule rule, RentChange singleChange, boolean januaryRentFree,
                                   List<LeaseLineInput> batchExtra, List<LeaseLineInput> singleExtra, String expectedRent) {
        List<LeaseLineInput> extra = batchExtra;
        List<UUID> viaBatch = List.of(posted(januaryRentFree), posted(januaryRentFree));
        List<UUID> byHand = List.of(posted(januaryRentFree), posted(januaryRentFree));

        List<LeaseBatch.RenewRow> rows = batch.renew(renewAll(viaBatch, rule, true, extra));
        assertThat(rows).allSatisfy(r -> assertThat(r.ok()).as(r.message()).isTrue());
        assertThat(rows).extracting(r -> r.newRent().stripTrailingZeros().toPlainString()).containsOnly(expectedRent);

        for (int i = 0; i < viaBatch.size(); i++) {
            LeaseDTO hand = renewal.renew(byHand.get(i), single(singleChange, singleExtra));
            // "Generate cheques" on the draft, four instalments as now (the fixtures carry no payment terms),
            // one-time charges on cheques of their own — the button's default since 2026-10-28.
            chequeGeneration.generate(hand.getId(), new com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest(
                    4, null, null, null, null, false, null));

            UUID fromBatch = rows.get(i).renewalLeaseId();
            assertThat(fromBatch).isEqualTo(renewalOf(viaBatch.get(i)));
            assertThat(draftShape(fromBatch)).as("draft %d, batch vs by hand", i).isEqualTo(draftShape(hand.getId()));

            postDraft(fromBatch);
            postDraft(hand.getId());
            assertThat(journals(fromBatch)).as("successor journals %d", i).isNotEmpty().isEqualTo(journals(hand.getId()));
            assertThat(journals(viaBatch.get(i))).as("predecessor journals %d", i).isEqualTo(journals(byHand.get(i)));
        }
    }

    // ------------------------------------------------------------------ bulk renew parity

    @Test
    void noChangeRenewsExactlyAsTheSingleRenewDoes() {
        assertRenewParity(new LeaseBatch.RentRule(LeaseBatch.RentRule.Mode.NONE, null, null), null, false, null, "80000");
    }

    @Test
    void aPercentageRoundsToWholeDirhamsExactlyAsTheSingleRenew() {
        // 80,000 × 1.0333333 = 82,666.664 → 82,667 (the single renew's own rounding)
        assertRenewParity(new LeaseBatch.RentRule(LeaseBatch.RentRule.Mode.PERCENT, new BigDecimal("3.33333"), null),
                new RentChange(RentChange.Mode.PERCENT, new BigDecimal("3.33333"), null), false, null, "82667");
    }

    @Test
    void anAddedAmountRoundsToWholeDirhamsAndDraftsAsTheTypedAmount() {
        // 80,000 + 1,234.50 = 81,234.50 → 81,235: drafted exactly as typing 81,235 into the single renew.
        assertRenewParity(new LeaseBatch.RentRule(LeaseBatch.RentRule.Mode.ADD, null, new BigDecimal("1234.50")),
                new RentChange(RentChange.Mode.AMOUNT, null, new BigDecimal("81235")), false, null, "81235");
    }

    @Test
    void rentFreeDoesNotRenewAndTheEscalationIsOnTheRentBeforeTheConcession() {
        // The predecessor's January was rent-free; the base is still 80,000 (headline less discount,
        // before the concession), the successor escalates that and starts with no rent-free period.
        assertRenewParity(new LeaseBatch.RentRule(LeaseBatch.RentRule.Mode.PERCENT, new BigDecimal("5"), null),
                new RentChange(RentChange.Mode.PERCENT, new BigDecimal("5"), null), true, null, "84000");
        UUID any = jdbc.queryForObject("select id from leases where renewed_from_lease_id is not null limit 1", UUID.class);
        assertThat(jdbc.queryForObject("select count(*) from lease_rent_free_periods where lease_id = ?", Integer.class, any))
                .isZero();
        assertThat(jdbc.queryForObject("select sum(rent_free_amount) from lease_lines where lease_id = ?", BigDecimal.class, any))
                .isEqualByComparingTo("0");
    }

    @Test
    void aRenewalFeeIsChargedOnEveryRenewalAsOnTheSingleRenew() {
        assertRenewParity(new LeaseBatch.RentRule(LeaseBatch.RentRule.Mode.NONE, null, null), null, false,
                List.of(line("RENEWAL_FEE", "1050")), "80000");
        // Client request 2026-10-28 (review of #452, I4): the fee is a cheque of its own on each
        // of the four renewals — two in bulk, two by hand — not added onto rent cheque 1.
        assertThat(jdbc.queryForObject("""
                select count(*) from cheques c join leases l on l.id = c.lease_id
                where l.tenant_id = ? and l.renewed_from_lease_id is not null
                  and c.row_kind = 'FEE' and c.amount = 1050""", Integer.class, f.tenantId())).isEqualTo(4);
    }

    @Test
    void aVatableFeeKeepsItsChargeTypesVatAsOnTheSingleRenew() {
        // Review P2-1: the single renew dialog sends the fee with its charge type's VAT default
        // (here on); the bulk dialog sends the same. Both draft a VAT-bearing fee line, and post the
        // same journals, output VAT included.
        jdbc.update("update charge_types set vat_applicable_default = true where tenant_id = ? and code = 'ADMIN_FEE'",
                f.tenantId());
        LeaseLineInput fee = new LeaseLineInput(null, "ADMIN_FEE", new BigDecimal("1000"), BigDecimal.ZERO,
                null, true, null, null, null);
        assertRenewParity(new LeaseBatch.RentRule(LeaseBatch.RentRule.Mode.NONE, null, null), null, false,
                List.of(fee), List.of(fee), "80000");
        UUID any = jdbc.queryForObject("""
                select ll.lease_id from lease_lines ll join charge_types ct on ct.id = ll.charge_type_id
                join leases l on l.id = ll.lease_id
                where ct.code = 'ADMIN_FEE' and l.renewed_from_lease_id is not null limit 1""", UUID.class);
        assertThat(jdbc.queryForObject("""
                select ll.vat_applicable from lease_lines ll join charge_types ct on ct.id = ll.charge_type_id
                where ll.lease_id = ? and ct.code = 'ADMIN_FEE'""", Boolean.class, any)).isTrue();
        // A fee sent with no flag takes the type's default on the server too.
        UUID a = posted(false);
        LeaseBatch.RenewRow row = batch.renew(renewAll(List.of(a), new LeaseBatch.RentRule(LeaseBatch.RentRule.Mode.NONE, null, null),
                false, List.of(line("ADMIN_FEE", "1000")))).get(0);
        assertThat(row.ok()).as(row.message()).isTrue();
        assertThat(jdbc.queryForObject("""
                select ll.vat_applicable from lease_lines ll join charge_types ct on ct.id = ll.charge_type_id
                where ll.lease_id = ? and ct.code = 'ADMIN_FEE'""", Boolean.class, row.renewalLeaseId())).isTrue();
    }

    @Test
    void theNewTermIsAsLongAsTheCurrentOneUnlessMonthsAreGiven() {
        UUID a = posted(f.property(), LocalDate.of(2027, 3, 15), LocalDate.of(2027, 9, 14), false);
        UUID b = posted(f.property(), LocalDate.of(2027, 3, 15), LocalDate.of(2027, 9, 14), false);
        LeaseBatch.RenewRow six = batch.renew(new LeaseBatch.RenewRequest(List.of(a),
                new LeaseBatch.RentRule(LeaseBatch.RentRule.Mode.NONE, null, null), null, R_CONTRACT, false, false, null)).get(0);
        assertThat(six.ok()).as(six.message()).isTrue();
        assertThat(six.startDate()).isEqualTo(LocalDate.of(2027, 9, 15));
        assertThat(six.endDate()).isEqualTo(LocalDate.of(2028, 3, 14));
        LeaseBatch.RenewRow year = batch.renew(new LeaseBatch.RenewRequest(List.of(b),
                new LeaseBatch.RentRule(LeaseBatch.RentRule.Mode.NONE, null, null), 12, R_CONTRACT, false, false, null)).get(0);
        assertThat(year.endDate()).isEqualTo(LocalDate.of(2028, 9, 14));
        assertThat(year.chequeRows()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from cheques where lease_id = ?", Integer.class, year.renewalLeaseId()))
                .isZero();
    }

    @Test
    void bulkRenewDraftsOnlyAndWritesNoJournal() {
        UUID a = posted(false);
        int before = jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ?", Integer.class, f.tenantId());
        LeaseBatch.RenewRow row = batch.renew(renewAll(List.of(a),
                new LeaseBatch.RentRule(LeaseBatch.RentRule.Mode.PERCENT, new BigDecimal("4"), null), true, null)).get(0);
        assertThat(row.ok()).isTrue();
        assertThat(jdbc.queryForObject("select status from leases where id = ?", String.class, row.renewalLeaseId())).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("select status from leases where id = ?", String.class, a)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ?", Integer.class, f.tenantId()))
                .isEqualTo(before);
    }

    // ------------------------------------------------------------------ partial failure

    @Test
    void oneRefusedContractDoesNotStopOrUndoTheOthers() {
        UUID first = posted(false);
        UUID alreadyRenewed = posted(false);
        UUID last = posted(false);
        UUID renewedBefore = renewal.renew(alreadyRenewed, single(null, null)).getId();
        UUID nowhere = UUID.randomUUID();

        List<LeaseBatch.RenewRow> rows = batch.renew(renewAll(List.of(first, alreadyRenewed, nowhere, last, first),
                new LeaseBatch.RentRule(LeaseBatch.RentRule.Mode.NONE, null, null), true, null));

        assertThat(rows).extracting(LeaseBatch.RenewRow::leaseId).containsExactly(first, alreadyRenewed, nowhere, last);
        assertThat(rows).extracting(LeaseBatch.RenewRow::ok).containsExactly(true, false, false, true);
        assertThat(rows.get(1).message()).contains("already been renewed");
        assertThat(rows.get(2).code()).isEqualTo("lease.notFound");
        assertThat(renewalOf(first)).isEqualTo(rows.get(0).renewalLeaseId());
        assertThat(renewalOf(last)).isEqualTo(rows.get(3).renewalLeaseId());
        assertThat(renewalOf(alreadyRenewed)).isEqualTo(renewedBefore);
    }

    @Test
    void aRefusalAfterTheDraftWasWrittenRollsBackThatContractOnly() {
        // The percentage needs the same term length; the 6-month override refuses it per row
        // before anything is written, and an ADD that leaves no rent is refused after the plan.
        UUID a = posted(false);
        UUID b = posted(false);
        List<LeaseBatch.RenewRow> rows = batch.renew(new LeaseBatch.RenewRequest(List.of(a, b),
                new LeaseBatch.RentRule(LeaseBatch.RentRule.Mode.PERCENT, new BigDecimal("5"), null), 6, R_CONTRACT, true, true, null));
        assertThat(rows).extracting(LeaseBatch.RenewRow::ok).containsExactly(false, false);
        assertThat(rows.get(0).message()).contains("term length changed");
        assertThat(jdbc.queryForObject("select count(*) from leases where renewed_from_lease_id in (?, ?)", Integer.class, a, b))
                .isZero();

        List<LeaseBatch.RenewRow> negative = batch.renew(renewAll(List.of(a),
                new LeaseBatch.RentRule(LeaseBatch.RentRule.Mode.ADD, null, new BigDecimal("-80000")), true, null));
        assertThat(negative.get(0).ok()).isFalse();
        assertThat(negative.get(0).message()).contains("no rent");
        assertThat(jdbc.queryForObject("select count(*) from leases where renewed_from_lease_id = ?", Integer.class, a)).isZero();
    }

    // ------------------------------------------------------------------ bulk terminate

    @Test
    void bulkTerminateEndsEachContractExactlyAsTheSingleTerminate() {
        LocalDate t = LocalDate.of(2027, 6, 30);
        List<UUID> viaBatch = List.of(posted(false), posted(true));
        List<UUID> byHand = List.of(posted(false), posted(true));

        List<LeaseBatch.TerminatePreviewRow> previews = batch.previewTerminations(
                new LeaseBatch.TerminateRequest(viaBatch, t, null));
        assertThat(previews).allSatisfy(p -> assertThat(p.ok()).as(p.message()).isTrue());
        for (int i = 0; i < viaBatch.size(); i++) {
            UUID id = viaBatch.get(i);
            assertThat(previews.get(i).preview()).usingRecursiveComparison()
                    .isEqualTo(tx.execute(s -> termination.preview(id, t)));
        }

        List<LeaseBatch.TerminateRow> rows = batch.terminate(new LeaseBatch.TerminateRequest(viaBatch, t, "Season move-out"),
                UUID.randomUUID());
        assertThat(rows).allSatisfy(r -> assertThat(r.ok()).as(r.message()).isTrue());
        assertThat(rows).extracting(LeaseBatch.TerminateRow::status).containsOnly("TERMINATED");

        for (int i = 0; i < viaBatch.size(); i++) {
            termination.terminate(byHand.get(i), new TerminateLeaseRequest(t, null, null, "Season move-out"), UUID.randomUUID());
            assertThat(journals(viaBatch.get(i))).as("journals %d", i).isEqualTo(journals(byHand.get(i)));
            assertThat(journals(viaBatch.get(i))).anyMatch(j -> j.startsWith("TCR|"));
            assertThat(chequeStatuses(viaBatch.get(i))).isEqualTo(chequeStatuses(byHand.get(i)));
            assertThat(jdbc.queryForObject("select terminated_on from leases where id = ?", LocalDate.class, viaBatch.get(i)))
                    .isEqualTo(t);
        }
    }

    @Test
    void theSplitThePreviewShowedIsTheSplitThatIsPostedAndAStaleOneIsRefused() {
        // Review P3-2: the confirm sends each preview's cheque lists; the single terminate refuses a
        // contract whose register no longer matches them, and posts exactly them otherwise.
        LocalDate t = LocalDate.of(2027, 6, 30);
        UUID viaBatch = posted(false);
        UUID byHand = posted(false);
        UUID stale = posted(false);
        var previews = batch.previewTerminations(new LeaseBatch.TerminateRequest(List.of(viaBatch, stale), t, null));
        java.util.Map<UUID, LeaseBatch.ChequeSplit> splits = new java.util.HashMap<>();
        for (var p : previews) {
            assertThat(p.ok()).as(p.message()).isTrue();
            splits.put(p.leaseId(), new LeaseBatch.ChequeSplit(
                    p.preview().chequesToReturn().stream().map(com.datagami.rentaxis.api.dto.cheque.ChequeDTO::id).toList(),
                    p.preview().chequesToKeep().stream().map(com.datagami.rentaxis.api.dto.cheque.ChequeDTO::id).toList()));
        }
        assertThat(splits.get(viaBatch).returnChequeIds()).isNotEmpty();
        // The stale contract's register changes after its preview: a kept cheque is no longer in either list.
        LeaseBatch.ChequeSplit s0 = splits.get(stale);
        splits.put(stale, new LeaseBatch.ChequeSplit(s0.returnChequeIds(), s0.keepChequeIds().subList(1, s0.keepChequeIds().size())));
        List<String> staleBefore = journals(stale);

        List<LeaseBatch.TerminateRow> rows = batch.terminate(
                new LeaseBatch.TerminateRequest(List.of(viaBatch, stale), t, null, splits), UUID.randomUUID());
        assertThat(rows).extracting(LeaseBatch.TerminateRow::ok).containsExactly(true, false);
        assertThat(rows.get(1).message()).contains("in neither list");
        assertThat(journals(stale)).isEqualTo(staleBefore);
        assertThat(jdbc.queryForObject("select status from leases where id = ?", String.class, stale)).isEqualTo("ACTIVE");

        var handPreview = tx.execute(st -> termination.preview(byHand, t));
        termination.terminate(byHand, new TerminateLeaseRequest(t,
                handPreview.chequesToReturn().stream().map(com.datagami.rentaxis.api.dto.cheque.ChequeDTO::id).toList(),
                handPreview.chequesToKeep().stream().map(com.datagami.rentaxis.api.dto.cheque.ChequeDTO::id).toList(), null),
                UUID.randomUUID());
        assertThat(journals(viaBatch)).isEqualTo(journals(byHand));
        assertThat(chequeStatuses(viaBatch)).isEqualTo(chequeStatuses(byHand));
    }

    @Test
    void thePreviewRunsReadOnly() {
        // Review P3-1: a batch preview row is a read-only transaction, as the single preview is.
        UUID a = posted(false);
        var seen = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        org.mockito.Mockito.doAnswer(inv -> {
            seen.set(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return inv.callRealMethod();
        }).when(terminationSpy).preview(org.mockito.ArgumentMatchers.eq(a), org.mockito.ArgumentMatchers.any());
        batch.previewTerminations(new LeaseBatch.TerminateRequest(List.of(a), LocalDate.of(2027, 6, 30), null));
        assertThat(seen.get()).isTrue();
    }

    @Test
    void withNoDateEachContractEndsOnItsOwnEndDate() {
        UUID a = posted(f.property(), LocalDate.of(2026, 12, 1), LocalDate.of(2027, 11, 30), false);
        List<LeaseBatch.TerminateRow> rows = batch.terminate(new LeaseBatch.TerminateRequest(List.of(a), null, null), UUID.randomUUID());
        assertThat(rows.get(0).ok()).as(rows.get(0).message()).isTrue();
        assertThat(rows.get(0).terminationDate()).isEqualTo(LocalDate.of(2027, 11, 30));
    }

    @Test
    void aContractEndingInsideTheLockedPeriodIsRefusedAndTheOthersGoThrough() {
        UUID june = posted(f.property(), LocalDate.of(2026, 7, 1), LocalDate.of(2027, 6, 30), false);
        UUID december = posted(false);
        List<String> juneBefore = journals(june);
        LockedBooks.lockOverPlanned(jdbc, LocalDate.of(2027, 6, 30));

        List<LeaseBatch.TerminatePreviewRow> previews = batch.previewTerminations(
                new LeaseBatch.TerminateRequest(List.of(june, december), null, null));
        assertThat(previews).extracting(LeaseBatch.TerminatePreviewRow::ok).containsExactly(false, true);
        assertThat(previews.get(0).message()).contains("books are locked");

        List<LeaseBatch.TerminateRow> rows = batch.terminate(
                new LeaseBatch.TerminateRequest(List.of(june, december), null, null), UUID.randomUUID());
        assertThat(rows).extracting(LeaseBatch.TerminateRow::ok).containsExactly(false, true);
        assertThat(rows.get(0).message()).contains("books are locked");
        // All or nothing: the refused contract is exactly as it was.
        assertThat(jdbc.queryForObject("select status from leases where id = ?", String.class, june)).isEqualTo("ACTIVE");
        assertThat(journals(june)).isEqualTo(juneBefore);
        assertThat(chequeStatuses(june)).containsOnly("REGISTERED");
        assertThat(jdbc.queryForObject("select status from leases where id = ?", String.class, december)).isEqualTo("TERMINATED");
    }

    @Test
    void aRenewalDraftedInTheBatchIsDiscardedByTheTerminationAsOnTheSingleDoor() {
        UUID a = posted(false);
        UUID draft = batch.renew(renewAll(List.of(a), new LeaseBatch.RentRule(LeaseBatch.RentRule.Mode.NONE, null, null),
                false, null)).get(0).renewalLeaseId();
        LeaseBatch.TerminatePreviewRow p = batch.previewTerminations(
                new LeaseBatch.TerminateRequest(List.of(a), LocalDate.of(2027, 6, 30), null)).get(0);
        assertThat(p.preview().notices()).anyMatch(n -> n.contains("will be discarded"));
        assertThat(batch.terminate(new LeaseBatch.TerminateRequest(List.of(a), LocalDate.of(2027, 6, 30), null),
                UUID.randomUUID()).get(0).ok()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from leases where id = ? and status = 'DRAFT'", Integer.class, draft)).isZero();
    }

    // ------------------------------------------------------------------ the queue's filters

    @Test
    void theQueueFiltersByExpiryWindowFloorAndWhetherARenewalExists() {
        Unit u0701 = f.createUnit(f.property(), "07-01");
        Unit u0702 = f.createUnit(f.property(), "07-02");
        Unit u1201 = f.createUnit(f.property(), "12-01");
        UUID oct0701 = postedOn(u0701, LocalDate.of(2026, 11, 1), LocalDate.of(2027, 10, 31));
        UUID oct1201 = postedOn(u1201, LocalDate.of(2026, 10, 15), LocalDate.of(2027, 10, 14));
        UUID nov0702 = postedOn(u0702, LocalDate.of(2026, 12, 1), LocalDate.of(2027, 11, 30));
        renewal.renew(oct1201, new RenewLeaseRequest(R_CONTRACT, LocalDate.of(2027, 10, 15), LocalDate.of(2028, 10, 14),
                null, true));

        LocalDate from = LocalDate.of(2027, 10, 1), to = LocalDate.of(2027, 10, 31);
        assertThat(queue(from, to, null, LeaseService.SuccessorState.ANY)).containsExactlyInAnyOrder(oct0701, oct1201);
        assertThat(queue(from, to, null, LeaseService.SuccessorState.NONE)).containsExactly(oct0701);
        assertThat(queue(from, to, null, LeaseService.SuccessorState.DRAFTED)).containsExactly(oct1201);
        assertThat(queue(null, null, "07", LeaseService.SuccessorState.ANY)).containsExactlyInAnyOrder(oct0701, nov0702);
        assertThat(queue(from, null, "12", LeaseService.SuccessorState.ANY)).containsExactly(oct1201);
        // The renewal state is on the row.
        LeaseDTO row = tx.execute(s -> leaseService.getAllLeasesPaged(null, null, null, null, null,
                new LeaseService.ExpiryFilter(from, to, null, LeaseService.SuccessorState.DRAFTED), PageRequest.of(0, 10)))
                .getContent().get(0);
        assertThat(row.getRenewedToLeaseId()).isEqualTo(renewalOf(oct1201));
        assertThat(row.getRenewedToStatus()).isEqualTo("DRAFT");
        // A wildcard typed as the floor matches itself, not everything.
        assertThat(queue(null, null, "%", LeaseService.SuccessorState.ANY)).isEmpty();
    }

    private UUID postedOn(Unit unit, LocalDate start, LocalDate end) {
        UUID id = f.draftLease(unit, f.createRenter("R " + unit.getUnitNumber()), CONTRACT.minusMonths(2), start, end,
                List.of(line("RENT", "60000")));
        f.generateGrid(id, 4, start);
        posting.post(id);
        return id;
    }

    private List<UUID> queue(LocalDate from, LocalDate to, String floor, LeaseService.SuccessorState successor) {
        return tx.execute(s -> leaseService.getAllLeasesPaged(null, null, null, null,
                List.of(LeaseStatus.DRAFT, LeaseStatus.PENDING_SIGNATURE),
                new LeaseService.ExpiryFilter(from, to, floor, successor),
                PageRequest.of(0, 50, Sort.by("endDate")))).getContent().stream().map(LeaseDTO::getId).toList();
    }

    // ------------------------------------------------------------------ scope and isolation

    @Test
    void aPropertyManagerSeesAndActsOnTheirOwnBuildingsOnly() {
        Property theirs = f.property();
        Property other = f.createProperty("OTH");
        UUID mine = posted(theirs, START, END, false);
        UUID notMine = posted(other, START, END, false);
        actAsManagerOf(theirs);

        assertThat(queue(END.withDayOfMonth(1), END, null, LeaseService.SuccessorState.ANY)).containsExactly(mine);

        List<LeaseBatch.RenewRow> rows = batch.renew(renewAll(List.of(mine, notMine),
                new LeaseBatch.RentRule(LeaseBatch.RentRule.Mode.NONE, null, null), false, null));
        assertThat(rows).extracting(LeaseBatch.RenewRow::ok).containsExactly(true, false);
        assertThat(rows.get(1).code()).isEqualTo("lease.notFound");
        assertThat(jdbc.queryForObject("select count(*) from leases where renewed_from_lease_id = ?", Integer.class, notMine)).isZero();

        List<LeaseBatch.TerminatePreviewRow> previews = batch.previewTerminations(
                new LeaseBatch.TerminateRequest(List.of(mine, notMine), LocalDate.of(2027, 6, 30), null));
        assertThat(previews).extracting(LeaseBatch.TerminatePreviewRow::ok).containsExactly(true, false);
        assertThat(previews.get(1).code()).isEqualTo("lease.notFound");
        List<LeaseBatch.TerminateRow> ended = batch.terminate(
                new LeaseBatch.TerminateRequest(List.of(notMine), LocalDate.of(2027, 6, 30), null), UUID.randomUUID());
        assertThat(ended.get(0).ok()).isFalse();
        assertThat(jdbc.queryForObject("select status from leases where id = ?", String.class, notMine)).isEqualTo("ACTIVE");
    }

    @Test
    void anotherOrganisationsContractsAreNotFoundAndUntouched() {
        UUID ours = posted(false);
        UUID ourTenant = f.tenantId();

        LeaseTestFixtures other = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService,
                accountService, propertyAccountService, chargeTypeService).bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
        UUID theirs = other.postedLease(CONTRACT, START, END, terms(), 4, null).lease().getId();
        List<String> theirJournals = journals(theirs);

        TenantContextHolder.setTenantId(ourTenant);
        LeaseTestFixtures.authenticateAsTenantAdmin();
        assertThat(queue(END.withDayOfMonth(1), END, null, LeaseService.SuccessorState.ANY)).containsExactly(ours);
        List<LeaseBatch.RenewRow> renewed = batch.renew(renewAll(List.of(theirs, ours),
                new LeaseBatch.RentRule(LeaseBatch.RentRule.Mode.PERCENT, new BigDecimal("5"), null), true, null));
        assertThat(renewed).extracting(LeaseBatch.RenewRow::ok).containsExactly(false, true);
        assertThat(renewed.get(0).code()).isEqualTo("lease.notFound");
        assertThat(batch.previewTerminations(new LeaseBatch.TerminateRequest(List.of(theirs), null, null)).get(0).code())
                .isEqualTo("lease.notFound");
        assertThat(batch.terminate(new LeaseBatch.TerminateRequest(List.of(theirs), null, null), UUID.randomUUID()).get(0).ok())
                .isFalse();

        assertThat(jdbc.queryForObject("select count(*) from leases where renewed_from_lease_id = ?", Integer.class, theirs)).isZero();
        assertThat(jdbc.queryForObject("select status from leases where id = ?", String.class, theirs)).isEqualTo("ACTIVE");
        assertThat(journals(theirs)).isEqualTo(theirJournals);
    }

    private void actAsManagerOf(Property property) {
        User pm = new User();
        pm.setEmail("pm-" + UUID.randomUUID() + "@t.io");
        pm.setName("PM");
        pm.setRole(UserRole.PROPERTY_MANAGER);
        pm.setStatus(UserStatus.ACTIVE);
        pm.setPasswordHash("x");
        pm.setTenantId(f.tenantId());
        UUID userId = userRepo.save(pm).getId();
        UserPropertyAssignment a = new UserPropertyAssignment();
        a.setUserId(userId);
        a.setPropertyId(property.getId());
        assignmentRepo.save(a);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_PROPERTY_MANAGER"))));
    }
}
