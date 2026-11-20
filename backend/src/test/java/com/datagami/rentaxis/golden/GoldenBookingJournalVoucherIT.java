package com.datagami.rentaxis.golden;

import com.datagami.rentaxis.api.dto.AmenityCreateRequest;
import com.datagami.rentaxis.api.dto.BookingCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotUpdateRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.BookingFeeService;
import com.datagami.rentaxis.core.service.BookingService;
import com.datagami.rentaxis.core.service.FacilityService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.IncomeLeafRule;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PenaltyAssessmentRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.vatLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Booking journal vouchers (spec 2026-11-19 §2, §3, §5 and the ledger rules): an
 * approved booking fee posts one {@code JV} on the approval date, Dr the renter's
 * receivable / Cr the bay's or amenity's income leaf (or the property's parking or
 * amenity income, or other income where a property has no amenity leaf yet) / Cr
 * output VAT; cancelling and crediting it leave no balance behind.
 */
@SpringBootTest
@Tag("golden")
class GoldenBookingJournalVoucherIT extends AbstractPostgresIT {

    private static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");
    /** The fixture lease runs 2026-05-01 to this day. */
    private static final LocalDate CONTRACT_END = LocalDate.of(2027, 4, 30);

    private static final String RECEIVABLE = "select coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0)"
            + " from journal_lines l join accounts a on a.id = l.account_id"
            + " where l.lease_id = ? and a.account_sub_type = 'RECEIVABLE'";

    @Autowired BookingService bookings;
    @Autowired FacilityService facilities;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountResolver accountResolver;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PenaltyAssessmentRepository assessments;
    @Autowired com.datagami.rentaxis.core.service.cheque.ChequeService chequeService;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private LeaseTestFixtures fixtures;
    private UUID renterUser;
    private UUID leaseId;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
        Renter r = fixtures.renter();
        renterUser = r.getUserId() != null ? r.getUserId() : UUID.randomUUID();
        r.setUserId(renterUser);
        renterRepo.save(r);
        leaseId = fixtures.postedLease(LocalDate.of(2026, 4, 20), LocalDate.of(2026, 5, 1), CONTRACT_END,
                List.of(vatLine("RENT", "120000")), 4, null).lease().getId();
        today = LocalDate.now(DUBAI);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------------ fixture helpers

    private UUID propertyId() {
        return fixtures.property().getId();
    }

    private Account leaf(AccountRole role) {
        return tx.execute(s -> accountResolver.resolve(role, propertyId()));
    }

    /** A fresh income leaf of the property under C-01-02 ("Other operating income"). */
    private Account newIncomeLeaf(String name) {
        return tx.execute(s -> accountService.createLeaf(name,
                accountRepo.findByCodeAndTenantId("C-01-02", fixtures.tenantId()).orElseThrow(), propertyId()));
    }

    private ParkingSpot bay(String number, String feeType, String fee, UUID incomeAccountId) {
        ParkingSpot s = facilities.createParkingSpot(fixtures.tenantId(), new ParkingSpotCreateRequest(
                propertyId(), number, "B1", null, null, null, incomeAccountId));
        return facilities.updateParkingSpot(fixtures.tenantId(), s.getId(),
                new ParkingSpotUpdateRequest(null, null, null, null, null, feeType, new BigDecimal(fee)));
    }

    private PropertyAmenity amenity(String name, String feeType, String fee, UUID incomeAccountId) {
        return facilities.createAmenity(fixtures.tenantId(), new AmenityCreateRequest(propertyId(), name,
                null, null, true, null, feeType, new BigDecimal(fee), incomeAccountId));
    }

    private BookingRequest bookParking(ParkingSpot spot, LocalDate from, LocalDate to) {
        return tx.execute(s -> bookings.create(fixtures.tenantId(), renterUser, fixtures.unit(), new BookingCreateRequest(
                BookingResourceType.PARKING_SPOT, spot.getId(), fixtures.unit().getId(), from, to, null, null, null)));
    }

    private BookingRequest bookAmenity(PropertyAmenity a, LocalDate day) {
        return tx.execute(s -> bookings.create(fixtures.tenantId(), renterUser, fixtures.unit(), new BookingCreateRequest(
                BookingResourceType.AMENITY, a.getId(), fixtures.unit().getId(), day, null,
                LocalTime.of(10, 0), LocalTime.of(12, 30), null)));
    }

    private BookingRequest approve(BookingRequest b) {
        return tx.execute(s -> bookings.approve(fixtures.tenantId(), b.getId(), UUID.randomUUID(), null));
    }

    /** The 1st of next month to the last day of the month after: two whole months. */
    private LocalDate twoMonthsFrom() {
        return today.withDayOfMonth(1).plusMonths(1);
    }

    private LocalDate twoMonthsTo() {
        return twoMonthsFrom().plusMonths(2).minusDays(1);
    }

    /** The ids of the journals whose source is this charge (source PENALTY, ledger rule 1). */
    private List<UUID> journalsOf(UUID chargeId) {
        return jdbc.queryForList("select id from journal_entries where source_type = 'PENALTY' and source_id = ?"
                + " order by created_at", UUID.class, chargeId);
    }

    /** The charge's journals plus their reversals (source REVERSAL, {@code reversal_of_id} = the original). */
    private static final String TRACE = "select id from journal_entries where (source_type = 'PENALTY' and source_id = ?)"
            + " or reversal_of_id in (select id from journal_entries where source_type = 'PENALTY' and source_id = ?)";

    private List<UUID> traceOf(UUID chargeId) {
        return jdbc.queryForList(TRACE, UUID.class, chargeId, chargeId);
    }

    private BigDecimal credit(UUID journalId, UUID accountId) {
        return jdbc.queryForObject("select coalesce(sum(credit), 0) - coalesce(sum(debit), 0) from journal_lines"
                + " where journal_entry_id = ? and account_id = ?", BigDecimal.class, journalId, accountId);
    }

    private BigDecimal receivableDebit(UUID journalId) {
        return jdbc.queryForObject("select coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) from journal_lines l"
                + " join accounts a on a.id = l.account_id where l.journal_entry_id = ?"
                + " and a.account_sub_type = 'RECEIVABLE'", BigDecimal.class, journalId);
    }

    private UUID onlyJournalOf(UUID chargeId) {
        List<UUID> js = journalsOf(chargeId);
        assertThat(js).as("one journal for the charge").hasSize(1);
        return js.get(0);
    }

    private int bookingJournalCount() {
        Integer n = jdbc.queryForObject("select count(*) from journal_entries j join penalty_assessments p"
                + " on j.source_type = 'PENALTY' and j.source_id = p.id where p.source_type = 'BOOKING'"
                + " and p.tenant_id = ?", Integer.class, fixtures.tenantId());
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void twoMonthParkingPostsOneJvToAdditionalParking() {
        ParkingSpot spot = bay("P-12", "PER_MONTH", "300", null);
        LocalDate from = twoMonthsFrom();
        LocalDate to = twoMonthsTo();
        BookingRequest b = bookParking(spot, from, to);
        UUID chargeId = approve(b).getChargeId();
        assertThat(chargeId).isNotNull();

        UUID jv = onlyJournalOf(chargeId);
        Map<String, Object> head = jdbc.queryForMap("select doc_type, entry_date, narration from journal_entries"
                + " where id = ?", jv);
        assertThat(head.get("doc_type")).isEqualTo("JV");
        assertThat(((java.sql.Date) head.get("entry_date")).toLocalDate()).isEqualTo(today);
        assertThat(receivableDebit(jv)).isEqualByComparingTo("630.00");
        assertThat(credit(jv, leaf(AccountRole.PARKING_INCOME).getId())).isEqualByComparingTo("600.00");
        assertThat(credit(jv, leaf(AccountRole.OUTPUT_VAT).getId())).isEqualByComparingTo("30.00");

        Map<String, Object> row = jdbc.queryForMap("select amount, narration from cheques where penalty_assessment_id = ?",
                chargeId);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("630.00");
        for (Object narration : List.of(row.get("narration"), head.get("narration"))) {
            assertThat((String) narration).startsWith("Booking fee - Parking ")
                    .contains(from.toString()).contains(to.toString());
        }
        assertThat(jdbc.queryForObject("select count(*) from notifications where tenant_id = ? and type = 'PENALTY_INCURRED'",
                Integer.class, fixtures.tenantId())).isZero();
    }

    /**
     * Renter cancel before the slot. Spec §5: only an approved amenity booking can be
     * cancelled (a parking booking is released, and its fee stands), so the 630 here is
     * an amenity at 600 a booking.
     */
    @Test
    void cancelledBeforeStartNetsToZero() {
        PropertyAmenity hall = amenity("Hall", "PER_BOOKING", "600", null);
        BookingRequest b = bookAmenity(hall, today.plusDays(10));
        UUID chargeId = approve(b).getChargeId();
        assertThat(receivableDebit(onlyJournalOf(chargeId))).isEqualByComparingTo("630.00");

        tx.executeWithoutResult(s -> bookings.cancel(fixtures.tenantId(), b.getId(), renterUser));

        assertThat(traceOf(chargeId)).as("the JV and its reversal").hasSize(2);
        List<Map<String, Object>> perAccount = jdbc.queryForList("select l.account_id,"
                + " coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) as net from journal_lines l"
                + " where l.journal_entry_id in (" + TRACE + ") group by l.account_id", chargeId, chargeId);
        assertThat(perAccount).isNotEmpty();
        assertThat(perAccount).allSatisfy(r -> assertThat((BigDecimal) r.get("net"))
                .as("account " + r.get("account_id")).isEqualByComparingTo("0"));
    }

    /** Staff cancel after the renter paid: the 630 stays as a credit on the renter's account, VAT on a credit note. */
    @Test
    void paidThenStaffCancelledLeavesCredit() {
        PropertyAmenity hall = amenity("Hall", "PER_BOOKING", "600", null);
        BookingRequest b = bookAmenity(hall, today.plusDays(10));
        UUID chargeId = approve(b).getChargeId();
        UUID row = tx.execute(s -> assessments.findById(chargeId).orElseThrow().getCollectionCheque().getId());
        chequeService.receive(row, com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest.on(today));
        BigDecimal before = jdbc.queryForObject(RECEIVABLE, BigDecimal.class, leaseId);

        tx.execute(s -> bookings.cancelByStaff(fixtures.tenantId(), b.getId(), UUID.randomUUID(), "Hall closed"));

        assertThat(jdbc.queryForObject(RECEIVABLE, BigDecimal.class, leaseId).subtract(before))
                .isEqualByComparingTo("-630.00");
        assertThat(jdbc.queryForObject("select count(*) from tax_invoices where kind = 'CREDIT_NOTE' and tenant_id = ?",
                Integer.class, fixtures.tenantId())).isOne();
    }

    @Test
    void amenityWithOwnLeafCreditsIt() {
        Account pool = newIncomeLeaf("Pool Income - " + fixtures.propertyName());
        PropertyAmenity a = amenity("Pool", "PER_BOOKING", "300", pool.getId());
        UUID chargeId = approve(bookAmenity(a, today.plusDays(7))).getChargeId();
        UUID jv = onlyJournalOf(chargeId);
        assertThat(credit(jv, pool.getId())).isEqualByComparingTo("300.00");
        assertThat(credit(jv, leaf(AccountRole.AMENITY_INCOME).getId())).isEqualByComparingTo("0");
    }

    @Test
    void amenityWithoutLeafCreditsAmenityIncome() {
        Account amenityIncome = leaf(AccountRole.AMENITY_INCOME);
        assertThat(amenityIncome.getName()).isEqualTo("Amenity Income - " + fixtures.propertyName());
        PropertyAmenity a = amenity("Pool", "PER_BOOKING", "300", null);
        UUID chargeId = approve(bookAmenity(a, today.plusDays(7))).getChargeId();
        UUID jv = onlyJournalOf(chargeId);
        assertThat(credit(jv, amenityIncome.getId())).isEqualByComparingTo("300.00");
    }

    @Test
    void amenityOnPropertyWithoutAmenityLeafFallsBackToOtherIncome() {
        int deleted = jdbc.update("delete from property_account_mappings where property_id = ? and role = 'AMENITY_INCOME'",
                propertyId());
        assertThat(deleted).isOne();
        Account none = tx.execute(s -> accountResolver.resolveOrNull(AccountRole.AMENITY_INCOME, propertyId()));
        assertThat(none).isNull();
        PropertyAmenity a = amenity("Pool", "PER_BOOKING", "300", null);
        UUID chargeId = approve(bookAmenity(a, today.plusDays(7))).getChargeId();
        UUID jv = onlyJournalOf(chargeId);
        assertThat(credit(jv, leaf(AccountRole.OTHER_INCOME).getId())).isEqualByComparingTo("300.00");
    }

    /** Review Focus 2: an undated request from an older client gets its dates on approval, and is quoted for them. */
    @Test
    void undatedParkingRequestIsRequotedOnApproval() {
        ParkingSpot spot = bay("P-20", "PER_MONTH", "300", null);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into booking_requests (id, tenant_id, property_id, resource_type, parking_spot_id, unit_id,"
                        + " renter_user_id, status, fee_amount, created_at, updated_at)"
                        + " values (?, ?, ?, 'PARKING_SPOT', ?, ?, ?, 'PENDING', 0, now(), now())",
                id, fixtures.tenantId(), propertyId(), spot.getId(), fixtures.unit().getId(), renterUser);

        BookingRequest approved = tx.execute(s -> bookings.approve(fixtures.tenantId(), id, UUID.randomUUID(), null));

        BookingRequest dated = new BookingRequest();
        dated.setPreferredDate(today);
        dated.setPreferredEndDate(CONTRACT_END);
        BigDecimal expected = BookingFeeService.quote(spot, dated);
        assertThat(expected).isPositive();
        assertThat(approved.getPreferredDate()).isEqualTo(today);
        assertThat(approved.getPreferredEndDate()).isEqualTo(CONTRACT_END);
        assertThat(approved.getChargeId()).isNotNull();
        assertThat(jdbc.queryForObject("select amount from penalty_assessments where id = ?", BigDecimal.class,
                approved.getChargeId())).isEqualByComparingTo(expected);
        assertThat(jdbc.queryForObject("select fee_amount from booking_requests where id = ?", BigDecimal.class, id))
                .isEqualByComparingTo(expected);
        assertThat(credit(onlyJournalOf(approved.getChargeId()), leaf(AccountRole.PARKING_INCOME).getId()))
                .isEqualByComparingTo(expected);
    }

    /** Review Focus 3: the bay's leaf was deactivated after it was set; approval is refused and nothing posts. */
    @Test
    void deactivatedIncomeLeafRefusesApproval() {
        Account bayLeaf = newIncomeLeaf("Bay P-30 Income");
        ParkingSpot spot = bay("P-30", "PER_MONTH", "300", bayLeaf.getId());
        BookingRequest b = bookParking(spot, twoMonthsFrom(), twoMonthsTo());
        assertThat(jdbc.update("update accounts set is_active = false where id = ?", bayLeaf.getId())).isOne();

        assertThatThrownBy(() -> approve(b))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting(e -> ((BusinessRuleViolationException) e).getCode())
                .isEqualTo(IncomeLeafRule.CODE);
        assertThat(jdbc.queryForObject("select status from booking_requests where id = ?", String.class, b.getId()))
                .isEqualTo("PENDING");
        assertThat(bookingJournalCount()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from penalty_assessments where source_type = 'BOOKING'"
                + " and tenant_id = ?", Integer.class, fixtures.tenantId())).isZero();
    }

    /** Review Focus 4: the fee the renter was quoted is charged, not the bay's fee at approval. */
    @Test
    void feeChangedWhilePendingChargesTheQuote() {
        ParkingSpot spot = bay("P-40", "PER_MONTH", "300", null);
        BookingRequest b = bookParking(spot, twoMonthsFrom(), twoMonthsTo());
        facilities.updateParkingSpot(fixtures.tenantId(), spot.getId(),
                new ParkingSpotUpdateRequest(null, null, null, null, null, "PER_MONTH", new BigDecimal("500")));

        UUID chargeId = approve(b).getChargeId();
        assertThat(credit(onlyJournalOf(chargeId), leaf(AccountRole.PARKING_INCOME).getId()))
                .isEqualByComparingTo("600.00");
    }

    /** Ledger rule 2: approval after a period lock posts on the approval day, never inside the lock. */
    @Test
    void noJournalInsideALockedPeriod() {
        LocalDate lastMonthEnd = today.withDayOfMonth(1).minusDays(1);
        assertThat(jdbc.update("update tenant_fiscal_settings set books_locked_through = ? where tenant_id = ?",
                lastMonthEnd, fixtures.tenantId())).isOne();
        ParkingSpot spot = bay("P-50", "PER_MONTH", "300", null);
        approve(bookParking(spot, twoMonthsFrom(), twoMonthsTo()));

        List<LocalDate> dates = jdbc.queryForList("select j.entry_date from journal_entries j join penalty_assessments p"
                + " on j.source_type = 'PENALTY' and j.source_id = p.id where p.source_type = 'BOOKING'"
                + " and p.tenant_id = ?", LocalDate.class, fixtures.tenantId());
        assertThat(dates).isNotEmpty().allSatisfy(d -> assertThat(d).isAfterOrEqualTo(today).isAfter(lastMonthEnd));
    }

    /** Spec §5: releasing a parking booking early keeps its fee — no refund, no new journal. */
    @Test
    void earlyReleaseKeepsTheFee() {
        ParkingSpot spot = bay("P-60", "PER_MONTH", "300", null);
        BookingRequest b = bookParking(spot, twoMonthsFrom(), twoMonthsTo());
        UUID chargeId = approve(b).getChargeId();
        onlyJournalOf(chargeId);

        tx.execute(s -> bookings.release(fixtures.tenantId(), b.getId(), UUID.randomUUID(), true));

        assertThat(jdbc.queryForObject("select status from booking_requests where id = ?", String.class, b.getId()))
                .isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("select status from penalty_assessments where id = ?", String.class, chargeId))
                .isEqualTo("APPROVED");
        assertThat(traceOf(chargeId)).as("the JV only: nothing reversed or added").hasSize(1);
    }

    /**
     * Spec ledger rules 1-6, end to end in one tenant: a mix of approved, cancelled, paid,
     * staff-booked and free bookings leaves the booking list, the charges and the journals
     * saying the same thing.
     */
    @Test
    void bookingsChargesAndJournalsReconcile() {
        // two-month PER_MONTH parking, approved: 600 + 30 VAT
        approve(bookParking(bay("P-70", "PER_MONTH", "300", null), twoMonthsFrom(), twoMonthsTo()));
        // amenity approved, then cancelled by the renter before its slot: nets to zero
        BookingRequest renterCancelled = bookAmenity(amenity("Hall", "PER_BOOKING", "600", null), today.plusDays(10));
        approve(renterCancelled);
        tx.executeWithoutResult(s -> bookings.cancel(fixtures.tenantId(), renterCancelled.getId(), renterUser));
        // amenity approved, paid, then cancelled by staff: the 630 stays as a credit
        BookingRequest paidCancelled = bookAmenity(amenity("Gym", "PER_BOOKING", "600", null), today.plusDays(11));
        UUID paidChargeId = approve(paidCancelled).getChargeId();
        UUID paidRow = tx.execute(s -> assessments.findById(paidChargeId).orElseThrow().getCollectionCheque().getId());
        chequeService.receive(paidRow, com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest.on(today));
        tx.execute(s -> bookings.cancelByStaff(fixtures.tenantId(), paidCancelled.getId(), UUID.randomUUID(), "Gym closed"));
        // staff on-behalf PER_DAY amenity (one day slot): 100 + 5 VAT
        PropertyAmenity court = amenity("Court", "PER_DAY", "100", null);
        BookingRequest onBehalf = tx.execute(s -> bookings.createOnBehalf(fixtures.tenantId(), UUID.randomUUID(),
                renterUser, fixtures.unit(), new BookingCreateRequest(BookingResourceType.AMENITY, court.getId(),
                        fixtures.unit().getId(), today.plusDays(20), null, LocalTime.of(9, 0), LocalTime.of(11, 0), null)));
        assertThat(onBehalf.getChargeId()).isNotNull();
        // a free booking: approved, no fee, no charge
        BookingRequest free = approve(bookAmenity(amenity("Garden", "FREE", "0", null), today.plusDays(12)));
        assertThat(free.getChargeId()).isNull();

        UUID tenant = fixtures.tenantId();
        // 1. approved, not-cancelled bookings with a fee = APPROVED BOOKING_FEE charges (count and amount)
        Map<String, Object> bookingSide = jdbc.queryForMap("select count(*) as n, coalesce(sum(fee_amount), 0) as total"
                + " from booking_requests where tenant_id = ? and status = 'APPROVED' and fee_amount > 0", tenant);
        Map<String, Object> chargeSide = jdbc.queryForMap("select count(*) as n, coalesce(sum(amount), 0) as total"
                + " from penalty_assessments where tenant_id = ? and source_type = 'BOOKING' and reason = 'BOOKING_FEE'"
                + " and status = 'APPROVED'", tenant);
        assertThat(((Number) bookingSide.get("n")).longValue()).isEqualTo(2L);
        assertThat(chargeSide.get("n")).isEqualTo(bookingSide.get("n"));
        assertThat((BigDecimal) chargeSide.get("total")).isEqualByComparingTo((BigDecimal) bookingSide.get("total"))
                .isEqualByComparingTo("700.00");
        assertThat(jdbc.queryForObject("select count(*) from booking_requests b where b.tenant_id = ?"
                + " and b.status = 'APPROVED' and b.fee_amount > 0 and not exists (select 1 from penalty_assessments p"
                + " where p.id = b.charge_id and p.status = 'APPROVED' and p.amount = b.fee_amount)", Integer.class, tenant))
                .as("every such booking has its own matching charge").isZero();

        // 2. those charges' journals' income credits sum to the same total
        BigDecimal income = jdbc.queryForObject("select coalesce(sum(l.credit) - sum(l.debit), 0) from journal_lines l"
                + " join accounts a on a.id = l.account_id join journal_entries j on j.id = l.journal_entry_id"
                + " join penalty_assessments p on j.source_type = 'PENALTY' and j.source_id = p.id"
                + " where p.tenant_id = ? and p.source_type = 'BOOKING' and p.status = 'APPROVED'"
                + " and a.account_type = 'INCOME'", BigDecimal.class, tenant);
        assertThat(income).isEqualByComparingTo((BigDecimal) bookingSide.get("total"));

        // 3. every charge's receivable debits equal its collection row's amount (fee + VAT)
        List<Map<String, Object>> perCharge = jdbc.queryForList("select p.id, p.amount, p.vat_amount, c.amount as row_amount,"
                + " (select coalesce(sum(l.debit), 0) from journal_lines l join accounts a on a.id = l.account_id"
                + "   join journal_entries j on j.id = l.journal_entry_id where j.source_type = 'PENALTY'"
                + "   and j.source_id = p.id and a.account_sub_type = 'RECEIVABLE') as receivable_debit"
                + " from penalty_assessments p join cheques c on c.id = p.collection_cheque_id"
                + " where p.tenant_id = ? and p.source_type = 'BOOKING'", tenant);
        assertThat(perCharge).hasSize(4).allSatisfy(r -> {
            assertThat((BigDecimal) r.get("receivable_debit")).as("charge " + r.get("id"))
                    .isEqualByComparingTo((BigDecimal) r.get("row_amount"));
            assertThat((BigDecimal) r.get("row_amount"))
                    .isEqualByComparingTo(((BigDecimal) r.get("amount")).add((BigDecimal) r.get("vat_amount")));
        });

        // 4. cancelled bookings' charges net to 0 on income and receivable, journal plus reversal
        List<UUID> cancelledCharges = jdbc.queryForList("select b.charge_id from booking_requests b where b.tenant_id = ?"
                + " and b.status = 'CANCELLED' and b.charge_id is not null", UUID.class, tenant);
        assertThat(cancelledCharges).hasSize(2);
        for (UUID chargeId : cancelledCharges) {
            assertThat(traceOf(chargeId)).as("the JV and its reversal").hasSize(2);
            for (String type : List.of("INCOME", "RECEIVABLE")) {
                String col = "INCOME".equals(type) ? "a.account_type" : "a.account_sub_type";
                BigDecimal net = jdbc.queryForObject("select coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0)"
                        + " from journal_lines l join accounts a on a.id = l.account_id where " + col + " = ?"
                        + " and l.journal_entry_id in (" + TRACE + ")", BigDecimal.class, type, chargeId, chargeId);
                assertThat(net).as(type + " net of charge " + chargeId).isEqualByComparingTo("0");
            }
        }
    }
}
