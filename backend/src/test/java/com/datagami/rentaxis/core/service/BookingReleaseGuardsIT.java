package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.BookingCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotUpdateRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PenaltyAssessmentRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR #468 review fixes on a lease without VAT: parking income falls back to OTHER_INCOME, an
 * early-release credit on a non-VAT lease posts no VAT, and no credit is posted (nor a row
 * lowered) once the contract is CLOSED or its settlement finalised.
 */
@SpringBootTest
class BookingReleaseGuardsIT extends AbstractPostgresIT {

    private static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");

    @Autowired BookingService bookings;
    @Autowired FacilityService facilities;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired AccountResolver accountResolver;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PenaltyAssessmentRepository assessments;
    @Autowired ChequeService chequeService;
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
        // No VAT on the lease, so the booking fee carries none.
        leaseId = fixtures.postedLease(LocalDate.of(2026, 4, 20), LocalDate.of(2026, 5, 1), LocalDate.of(2027, 4, 30),
                List.of(line("RENT", "120000")), 4, null).lease().getId();
        today = LocalDate.now(DUBAI);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private UUID propertyId() {
        return fixtures.property().getId();
    }

    private Account leaf(AccountRole role) {
        return tx.execute(s -> accountResolver.resolve(role, propertyId()));
    }

    private ParkingSpot bay(String number, String feeType, String fee) {
        ParkingSpot s = facilities.createParkingSpot(fixtures.tenantId(), new ParkingSpotCreateRequest(
                propertyId(), number, "B1", null, null, null, null));
        return facilities.updateParkingSpot(fixtures.tenantId(), s.getId(),
                new ParkingSpotUpdateRequest(null, null, null, null, null, feeType, new BigDecimal(fee)));
    }

    private BookingRequest approve(ParkingSpot spot, LocalDate from, LocalDate to) {
        BookingRequest b = tx.execute(s -> bookings.create(fixtures.tenantId(), renterUser, fixtures.unit(),
                new BookingCreateRequest(BookingResourceType.PARKING_SPOT, spot.getId(), fixtures.unit().getId(),
                        from, to, null, null, null)));
        return tx.execute(s -> bookings.approve(fixtures.tenantId(), b.getId(), UUID.randomUUID(), null));
    }

    /** A two-month PER_MONTH 300 booking approved today, then moved back a month (dates only), as in the golden IT. */
    private BookingRequest approvedAMonthAgo(String number) {
        BookingRequest b = approve(bay(number, "PER_MONTH", "300"), today, today.plusMonths(2).minusDays(1));
        LocalDate start = today.minusMonths(1);
        assertThat(jdbc.update("update booking_requests set preferred_date = ?, preferred_end_date = ? where id = ?",
                start, start.plusMonths(2).minusDays(1), b.getId())).isOne();
        return b;
    }

    private BigDecimal expectedCredit() {
        LocalDate start = today.minusMonths(1);
        return BookingFeeService.creditForRelease("PER_MONTH", new BigDecimal("300"), new BigDecimal("600.00"),
                start, start.plusMonths(2).minusDays(1), today);
    }

    private BookingRequest release(BookingRequest b) {
        return tx.execute(s -> bookings.release(fixtures.tenantId(), b.getId(), UUID.randomUUID(), true));
    }

    private UUID collectionRowOf(UUID chargeId) {
        return tx.execute(s -> assessments.findById(chargeId).orElseThrow().getCollectionCheque().getId());
    }

    private List<UUID> journalsOf(UUID chargeId) {
        return jdbc.queryForList("select id from journal_entries where source_type = 'PENALTY' and source_id = ?"
                + " order by created_at", UUID.class, chargeId);
    }

    private BigDecimal credit(UUID journalId, UUID accountId) {
        return jdbc.queryForObject("select coalesce(sum(credit), 0) - coalesce(sum(debit), 0) from journal_lines"
                + " where journal_entry_id = ? and account_id = ?", BigDecimal.class, journalId, accountId);
    }

    private void finaliseSettlement() {
        jdbc.update("insert into lease_settlements (id, tenant_id, lease_id, status) values (?, ?, ?, 'FINALIZED')",
                UUID.randomUUID(), fixtures.tenantId(), leaseId);
    }

    private static String code(Throwable e) {
        return ((BusinessRuleViolationException) e).getCode();
    }

    /** Review m5: a property with no PARKING_INCOME leaf posts the fee to OTHER_INCOME, as before this feature. */
    @Test
    void parkingOnAPropertyWithoutAParkingLeafFallsBackToOtherIncome() {
        int deleted = jdbc.update("delete from property_account_mappings where property_id = ? and role = 'PARKING_INCOME'",
                propertyId());
        assertThat(deleted).isOne();
        Account none = tx.execute(s -> accountResolver.resolveOrNull(AccountRole.PARKING_INCOME, propertyId()));
        assertThat(none).isNull();

        BookingRequest b = approve(bay("P-90", "PER_BOOKING", "250"), today.plusDays(3), today.plusDays(5));

        List<UUID> js = journalsOf(b.getChargeId());
        assertThat(js).hasSize(1);
        assertThat(credit(js.get(0), leaf(AccountRole.OTHER_INCOME).getId())).isEqualByComparingTo("250.00");
    }

    /** Review m8: a credit on a lease without VAT posts no VAT line, no credit note, and lowers the row by the net. */
    @Test
    void earlyReleaseOnANonVatLeaseCreditsNoVat() {
        BookingRequest b = approvedAMonthAgo("P-91");
        UUID chargeId = b.getChargeId();
        assertThat(jdbc.queryForObject("select coalesce(vat_amount, 0) from penalty_assessments where id = ?",
                BigDecimal.class, chargeId)).isEqualByComparingTo("0");
        UUID row = collectionRowOf(chargeId);
        assertThat(jdbc.queryForObject("select amount from cheques where id = ?", BigDecimal.class, row))
                .isEqualByComparingTo("600.00");
        BigDecimal credit = expectedCredit();
        assertThat(credit).isPositive();

        release(b);

        List<UUID> js = journalsOf(chargeId);
        assertThat(js).hasSize(2);
        UUID cj = js.get(1);
        assertThat(credit(cj, leaf(AccountRole.PARKING_INCOME).getId())).isEqualByComparingTo(credit.negate());
        assertThat(jdbc.queryForObject("select count(*) from journal_lines where journal_entry_id = ?", Integer.class, cj))
                .as("Dr income / Cr receivable only").isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from tax_invoices where kind = 'CREDIT_NOTE' and tenant_id = ?",
                Integer.class, fixtures.tenantId())).isZero();
        assertThat(jdbc.queryForObject("select amount from cheques where id = ?", BigDecimal.class, row))
                .isEqualByComparingTo(new BigDecimal("600.00").subtract(credit));
        assertThat(jdbc.queryForObject("select credited_amount from penalty_assessments where id = ?", BigDecimal.class,
                chargeId)).isEqualByComparingTo(credit);
    }

    /** Review m3: once the contract is CLOSED, a release that would credit is refused and nothing posts. */
    @Test
    void releaseOnAClosedLeaseIsRefused() {
        BookingRequest b = approvedAMonthAgo("P-92");
        assertThat(jdbc.update("update leases set status = 'CLOSED' where id = ?", leaseId)).isOne();

        assertThatThrownBy(() -> release(b))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting(BookingReleaseGuardsIT::code).isEqualTo("booking.leaseSettled");
        assertThat(journalsOf(b.getChargeId())).hasSize(1);
        assertThat(jdbc.queryForObject("select status from booking_requests where id = ?", String.class, b.getId()))
                .isEqualTo("APPROVED");
    }

    /** Review m2/m3: a finalised settlement refuses the credit, and the row is not lowered behind it. */
    @Test
    void releaseAfterTheSettlementIsFinalisedIsRefused() {
        BookingRequest b = approvedAMonthAgo("P-93");
        UUID row = collectionRowOf(b.getChargeId());
        finaliseSettlement();

        assertThatThrownBy(() -> release(b))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting(BookingReleaseGuardsIT::code).isEqualTo("booking.leaseSettled");
        assertThat(journalsOf(b.getChargeId())).hasSize(1);
        assertThat(jdbc.queryForObject("select amount from cheques where id = ?", BigDecimal.class, row))
                .isEqualByComparingTo("600.00");
    }

    /** Review m2: the row-lowering door itself refuses a finalised settlement, as the other row mutations do. */
    @Test
    void reduceUnpaidBySystemRefusesAFinalisedSettlement() {
        BookingRequest b = approvedAMonthAgo("P-94");
        UUID row = collectionRowOf(b.getChargeId());
        finaliseSettlement();

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                chequeService.reduceUnpaidBySystem(row, new BigDecimal("100.00"), today, "test")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting(BookingReleaseGuardsIT::code).isEqualTo("cheque.settlementFinalized");
        Map<String, Object> r = jdbc.queryForMap("select amount, status from cheques where id = ?", row);
        assertThat((BigDecimal) r.get("amount")).isEqualByComparingTo("600.00");
        assertThat(r.get("status")).isEqualTo("REGISTERED");
    }
}
