package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateTicketDTO;
import com.datagami.rentaxis.api.dto.MaintenanceTicketDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.MaintenanceTicketRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR #342 review I2. A ticket staff log on a renter's behalf (#19) belongs to
 * that renter: they see it, they hold the closure OTP and they are told when it
 * is resolved; the staff member who logged it never sees the code. And the
 * six-digit code cannot be guessed: after five wrong tries it is discarded
 * until a new one is issued.
 */
@SpringBootTest
class TicketClosureOtpIT extends AbstractPostgresIT {

    @Autowired MaintenanceTicketService tickets;
    @Autowired MaintenanceTicketRepository ticketRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UserRepository userRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.datagami.rentaxis.domain.repository.UnitRepository unitRepo;
    @Autowired com.datagami.rentaxis.domain.repository.LeaseRepository leaseRepo;
    @Autowired com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository assignmentRepo;

    private UUID tenantId;
    private User staff;
    private User renterUser;
    private Renter renter;
    private UUID propertyId;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("OTP-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);

        staff = user(UserRole.PROPERTY_MANAGER);
        renterUser = user(UserRole.RENTER);
        Renter r = new Renter();
        r.setNameEn("Rajesh Kumar");
        r.setUserId(renterUser.getId());
        renter = renterRepo.save(r);

        Property p = new Property();
        p.setNameEn("Tower " + UUID.randomUUID());
        p.setEmirate(Emirate.DUBAI);
        propertyId = propertyRepo.save(p).getId();

        // The PM runs this property, so the property-scoped routes let them act.
        com.datagami.rentaxis.domain.entity.UserPropertyAssignment a =
                new com.datagami.rentaxis.domain.entity.UserPropertyAssignment();
        a.setUserId(staff.getId());
        a.setPropertyId(propertyId);
        assignmentRepo.save(a);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                staff.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_PROPERTY_MANAGER"))));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private User user(UserRole role) {
        User u = new User();
        u.setEmail("otp-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    /** A ticket staff logged, optionally for the renter, taken to RESOLVED. */
    private UUID resolvedTicket(boolean onBehalfOfRenter) {
        CreateTicketDTO dto = new CreateTicketDTO();
        dto.setPropertyId(propertyId);
        dto.setTitle("Leaking tap");
        if (onBehalfOfRenter) dto.setOnBehalfOfRenterId(renter.getId());
        UUID id = tickets.createTicket(dto, staff.getId()).getId();
        tickets.assignTicket(id, staff.getId(), staff.getId());
        tickets.updateStatus(id, "IN_PROGRESS", staff.getId());
        tickets.updateStatus(id, "RESOLVED", staff.getId());
        return id;
    }

    private String storedOtp(UUID ticketId) {
        return jdbc.queryForObject("SELECT closure_otp FROM maintenance_tickets WHERE id = ?", String.class, ticketId);
    }

    private static String wrong(String otp) {
        return otp.equals("000000") ? "111111" : "000000";
    }

    @Test
    void theRenterAStaffTicketWasLoggedForHoldsTheOtpAndStaffNeverSeeIt() {
        UUID id = resolvedTicket(true);

        assertThat(tickets.getTicket(id, staff.getId()).getClosureOtp()).isNull();
        assertThat(tickets.getTickets(staff.getId(), "TENANT_ADMIN", null))
                .filteredOn(t -> t.getId().equals(id))
                .extracting(MaintenanceTicketDTO::getClosureOtp).containsOnlyNulls();

        String renterSees = tickets.getTicket(id, renterUser.getId()).getClosureOtp();
        assertThat(renterSees).isNotNull().isEqualTo(storedOtp(id));
    }

    @Test
    void theRenterSeesTheTicketInTheirOwnList() {
        UUID id = resolvedTicket(true);

        List<MaintenanceTicketDTO> mine = tickets.getTickets(renterUser.getId(), "RENTER", null);

        assertThat(mine).extracting(MaintenanceTicketDTO::getId).contains(id);
        assertThat(mine).filteredOn(t -> t.getId().equals(id))
                .extracting(MaintenanceTicketDTO::getClosureOtp).doesNotContainNull();
    }

    @Test
    void theResolvedNoticeGoesToTheRenterNotToStaff() {
        UUID id = resolvedTicket(true);

        List<UUID> recipients = jdbc.queryForList(
                "SELECT user_id FROM notifications WHERE reference_id = ? AND type = 'TICKET_RESOLVED'", UUID.class, id);
        assertThat(recipients).containsExactly(renterUser.getId());
    }

    /** Staff reporting with no renter at all (no on-behalf renter, no contract) stay the holder. */
    @Test
    void aTicketStaffLoggedForNobodyStillShowsItsReporterTheOtp() {
        UUID id = resolvedTicket(false);

        assertThat(tickets.getTicket(id, staff.getId()).getClosureOtp()).isEqualTo(storedOtp(id));
    }

    @Test
    void fiveWrongOtpsDiscardTheCodeUntilANewOneIsIssued() {
        UUID id = resolvedTicket(true);
        String otp = storedOtp(id);

        assertThatThrownBy(() -> tickets.closeWithOtp(id, wrong(otp), staff.getId()))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessage("Invalid OTP");
        // The failed try is counted even though the request failed.
        assertThat(jdbc.queryForObject("SELECT closure_otp_failed_attempts FROM maintenance_tickets WHERE id = ?",
                Integer.class, id)).isEqualTo(1);
        for (int i = 2; i < MaintenanceTicketService.MAX_OTP_ATTEMPTS; i++) {
            assertThatThrownBy(() -> tickets.closeWithOtp(id, wrong(otp), staff.getId()))
                    .hasMessage("Invalid OTP");
        }
        assertThatThrownBy(() -> tickets.closeWithOtp(id, wrong(otp), staff.getId()))
                .hasMessageContaining("Too many wrong OTPs");

        // The right code no longer works: it was discarded.
        assertThat(storedOtp(id)).isNull();
        assertThatThrownBy(() -> tickets.closeWithOtp(id, otp, staff.getId()))
                .isInstanceOf(BusinessRuleViolationException.class);

        // A new one goes to the renter; staff still do not see it.
        MaintenanceTicketDTO reissued = tickets.reissueClosureOtp(id, staff.getId());
        assertThat(reissued.getClosureOtp()).isNull();
        String fresh = tickets.getTicket(id, renterUser.getId()).getClosureOtp();
        assertThat(fresh).isNotNull();
        assertThat(jdbc.queryForObject("SELECT closure_otp_failed_attempts FROM maintenance_tickets WHERE id = ?",
                Integer.class, id)).isZero();

        assertThat(tickets.closeWithOtp(id, fresh, staff.getId()).getStatus()).isEqualTo("CLOSED");
    }

    // ---- The status route cannot skip the OTP ----

    private static final String CLOSED_WITHOUT_OTP = "Ticket closed without OTP (no renter to confirm)";

    private String status(UUID ticketId) {
        return jdbc.queryForObject("SELECT status FROM maintenance_tickets WHERE id = ?", String.class, ticketId);
    }

    private List<String> historyNotes(UUID ticketId) {
        return jdbc.queryForList("SELECT notes FROM ticket_history WHERE ticket_id = ? ORDER BY created_at",
                String.class, ticketId);
    }

    private void assertRefusedThroughStatusRoute(UUID id) {
        // The detail page offers no "Close ticket": OTP closure is the path.
        assertThat(tickets.getTicket(id, staff.getId()).isClosableWithoutOtp()).isFalse();
        assertThatThrownBy(() -> tickets.updateStatus(id, "CLOSED", staff.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("OTP closure");
        assertThat(status(id)).isEqualTo("RESOLVED");
        assertThat(storedOtp(id)).isNotNull();
    }

    private void assertClosedWithoutOtp(UUID id) {
        MaintenanceTicketDTO before = tickets.getTicket(id, staff.getId());
        assertThat(before.isClosableWithoutOtp()).isTrue();
        assertThat(before.isOtpLocked()).isFalse();
        MaintenanceTicketDTO closed = tickets.updateStatus(id, "CLOSED", staff.getId());
        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(storedOtp(id)).isNull();
        assertThat(historyNotes(id)).last().isEqualTo(CLOSED_WITHOUT_OTP);
    }

    @Test
    void staffCannotCloseAResolvedTicketThroughTheStatusRouteWhenItsRenterHoldsTheOtp() {
        UUID id = resolvedTicket(true);

        assertRefusedThroughStatusRoute(id);

        // The OTP route still works.
        assertThat(tickets.closeWithOtp(id, storedOtp(id), staff.getId()).getStatus()).isEqualTo("CLOSED");
    }

    @Test
    void staffCannotCloseARenterReportedTicketThroughTheStatusRoute() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                renterUser.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_RENTER"))));
        CreateTicketDTO dto = new CreateTicketDTO();
        dto.setPropertyId(propertyId);
        dto.setTitle("Door jammed");
        UUID id = tickets.createTicket(dto, renterUser.getId()).getId();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                staff.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_PROPERTY_MANAGER"))));
        tickets.assignTicket(id, staff.getId(), staff.getId());
        tickets.updateStatus(id, "IN_PROGRESS", staff.getId());
        tickets.updateStatus(id, "RESOLVED", staff.getId());

        assertRefusedThroughStatusRoute(id);
    }

    @Test
    void anOnBehalfRenterWithNoPortalAccountLetsStaffCloseThroughTheStatusRoute() {
        Renter noAccount = new Renter();
        noAccount.setNameEn("Walk-in Renter");
        noAccount = renterRepo.save(noAccount);
        CreateTicketDTO dto = new CreateTicketDTO();
        dto.setPropertyId(propertyId);
        dto.setTitle("Broken AC");
        dto.setOnBehalfOfRenterId(noAccount.getId());
        UUID id = tickets.createTicket(dto, staff.getId()).getId();
        tickets.assignTicket(id, staff.getId(), staff.getId());
        tickets.updateStatus(id, "IN_PROGRESS", staff.getId());
        tickets.updateStatus(id, "RESOLVED", staff.getId());

        assertClosedWithoutOtp(id);
    }

    @Test
    void aTicketWithNoRenterBehindItLetsStaffCloseThroughTheStatusRoute() {
        UUID id = resolvedTicket(false);

        assertClosedWithoutOtp(id);
    }

    @Test
    void aTenantThatDoesNotAskForOtpsClosesThroughTheStatusRouteAsBefore() {
        LandlordOrg org = orgRepo.findById(tenantId).orElseThrow();
        org.setTicketOtpRequired(false);
        orgRepo.save(org);
        UUID id = resolvedTicket(true);

        // Staff see the close action; the renter, who cannot call the route, does not.
        assertThat(tickets.getTicket(id, staff.getId()).isClosableWithoutOtp()).isTrue();
        as(renterUser);
        assertThat(tickets.getTicket(id, renterUser.getId()).isClosableWithoutOtp()).isFalse();
        as(staff);
        assertThat(tickets.updateStatus(id, "CLOSED", staff.getId()).getStatus()).isEqualTo("CLOSED");
        // Nothing left to close.
        assertThat(tickets.getTicket(id, staff.getId()).isClosableWithoutOtp()).isFalse();
        assertThat(historyNotes(id)).last().isEqualTo("Status changed: RESOLVED → CLOSED");
    }

    // ---- Re-review I1: a contract's renter holds the OTP of tickets raised on it ----

    private void as(User u) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                u.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()))));
    }

    private UUID leaseFor(Renter r) {
        com.datagami.rentaxis.domain.entity.Unit u = new com.datagami.rentaxis.domain.entity.Unit();
        u.setProperty(propertyRepo.findById(propertyId).orElseThrow());
        u.setUnitNumber("U-" + UUID.randomUUID());
        u = unitRepo.save(u);
        com.datagami.rentaxis.domain.entity.Lease l = new com.datagami.rentaxis.domain.entity.Lease();
        l.setUnit(u);
        l.setRenter(r);
        l.setStartDate(java.time.LocalDate.of(2026, 1, 1));
        l.setEndDate(java.time.LocalDate.of(2026, 12, 31));
        l.setStatus(com.datagami.rentaxis.domain.entity.enums.LeaseStatus.ACTIVE);
        l.setRentAmount(new java.math.BigDecimal("1200.00"));
        l.setDepositAmount(new java.math.BigDecimal("0.00"));
        return leaseRepo.save(l).getId();
    }

    @Test
    void aTicketStaffRaiseOnAContractBelongsToItsRenterNotToStaff() {
        CreateTicketDTO dto = new CreateTicketDTO();
        dto.setPropertyId(propertyId);
        dto.setTitle("AC leaking");
        dto.setLeaseId(leaseFor(renter));
        UUID id = tickets.createTicket(dto, staff.getId()).getId();
        tickets.assignTicket(id, staff.getId(), staff.getId());
        tickets.updateStatus(id, "IN_PROGRESS", staff.getId());
        tickets.updateStatus(id, "RESOLVED", staff.getId());

        assertThat(tickets.getTicket(id, staff.getId()).getClosureOtp()).isNull();
        assertRefusedThroughStatusRoute(id);

        as(renterUser);
        assertThat(tickets.getTicket(id, renterUser.getId()).getClosureOtp()).isEqualTo(storedOtp(id));
        assertThat(tickets.getTickets(renterUser.getId(), "RENTER", null))
                .extracting(MaintenanceTicketDTO::getId).contains(id);
    }

    @Test
    void aRenterCannotRaiseATicketOnSomeoneElsesContract() {
        UUID lease = leaseFor(renter);
        User other = user(UserRole.RENTER);
        Renter otherRenter = new Renter();
        otherRenter.setNameEn("Other Renter");
        otherRenter.setUserId(other.getId());
        renterRepo.save(otherRenter);
        as(other);
        CreateTicketDTO dto = new CreateTicketDTO();
        dto.setPropertyId(propertyId);
        dto.setTitle("Not my flat");
        dto.setLeaseId(lease);

        assertThatThrownBy(() -> tickets.createTicket(dto, other.getId()))
                .isInstanceOf(com.datagami.rentaxis.api.exception.NotFoundException.class);
    }

    // ---- Re-review I2/I3: re-issue is capped, scoped, and refused with nobody to send to ----

    private long reissues(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM ticket_history WHERE ticket_id = ? AND action = 'OTP_REISSUED'",
                Long.class, id);
    }

    @Test
    void aClosureOtpCannotBeReissuedWhenNoRenterCanReceiveIt() {
        Renter noAccount = new Renter();
        noAccount.setNameEn("Walk-in Renter");
        noAccount = renterRepo.save(noAccount);
        CreateTicketDTO dto = new CreateTicketDTO();
        dto.setPropertyId(propertyId);
        dto.setTitle("Broken AC");
        dto.setOnBehalfOfRenterId(noAccount.getId());
        UUID onBehalf = tickets.createTicket(dto, staff.getId()).getId();
        tickets.assignTicket(onBehalf, staff.getId(), staff.getId());
        tickets.updateStatus(onBehalf, "IN_PROGRESS", staff.getId());
        tickets.updateStatus(onBehalf, "RESOLVED", staff.getId());
        UUID noRenter = resolvedTicket(false);

        for (UUID id : List.of(onBehalf, noRenter)) {
            assertThatThrownBy(() -> tickets.reissueClosureOtp(id, staff.getId()))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("No renter can receive")
                    .hasMessageContaining("status route");
            assertThat(reissues(id)).isZero();
        }
    }

    @Test
    void aClosureOtpCanBeReissuedAtMostThreeTimesADay() {
        UUID id = resolvedTicket(true);
        for (int i = 0; i < MaintenanceTicketService.MAX_OTP_REISSUES_PER_DAY; i++) {
            tickets.reissueClosureOtp(id, staff.getId());
        }
        String last = storedOtp(id);

        assertThatThrownBy(() -> tickets.reissueClosureOtp(id, staff.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("at most 3 times in 24 hours");
        assertThat(storedOtp(id)).isEqualTo(last);
        assertThat(reissues(id)).isEqualTo(3);
    }

    @Test
    void aPropertyManagerCannotReissueForAPropertyTheyDoNotRun() {
        UUID id = resolvedTicket(true);
        User otherPm = user(UserRole.PROPERTY_MANAGER);
        as(otherPm);

        assertThatThrownBy(() -> tickets.reissueClosureOtp(id, otherPm.getId()))
                .isInstanceOf(com.datagami.rentaxis.api.exception.NotFoundException.class);
        assertThat(reissues(id)).isZero();
    }

    @Test
    void tenWrongOtpsInTotalLockOtpClosureForGoodAndOnlyAnAdminCanClose() {
        UUID id = resolvedTicket(true);
        // Five wrong on the first code discards it...
        for (int i = 0; i < MaintenanceTicketService.MAX_OTP_ATTEMPTS; i++) {
            String otp = storedOtp(id);
            assertThatThrownBy(() -> tickets.closeWithOtp(id, wrong(otp), staff.getId()))
                    .isInstanceOf(BusinessRuleViolationException.class);
        }
        // ...a re-issue resets the per-code count but not the lifetime one...
        tickets.reissueClosureOtp(id, staff.getId());
        for (int i = 1; i < MaintenanceTicketService.MAX_OTP_ATTEMPTS; i++) {
            String otp = storedOtp(id);
            assertThatThrownBy(() -> tickets.closeWithOtp(id, wrong(otp), staff.getId()))
                    .hasMessage("Invalid OTP");
        }
        String otp = storedOtp(id);
        // ...and the tenth wrong one in total locks it.
        assertThatThrownBy(() -> tickets.closeWithOtp(id, wrong(otp), staff.getId()))
                .hasMessageContaining("OTP closure is now locked");
        assertThat(jdbc.queryForObject("SELECT closure_otp_total_failed_attempts FROM maintenance_tickets WHERE id = ?",
                Integer.class, id)).isEqualTo(MaintenanceTicketService.MAX_TOTAL_OTP_FAILURES);

        // Locked: no code, no re-issue, and the right old code is useless.
        assertThat(storedOtp(id)).isNull();
        assertThatThrownBy(() -> tickets.reissueClosureOtp(id, staff.getId()))
                .hasMessageContaining("OTP closure is locked");
        assertThatThrownBy(() -> tickets.closeWithOtp(id, otp, staff.getId()))
                .hasMessageContaining("OTP closure is locked");

        // Reopening and resolving again does not unlock it or mint a new code.
        tickets.updateStatus(id, "REOPENED", staff.getId());
        tickets.updateStatus(id, "IN_PROGRESS", staff.getId());
        tickets.updateStatus(id, "RESOLVED", staff.getId());
        assertThat(storedOtp(id)).isNull();
        assertThatThrownBy(() -> tickets.reissueClosureOtp(id, staff.getId()))
                .hasMessageContaining("OTP closure is locked");

        // The DTO says so: locked for staff, closable only by an admin, and
        // neither flag reaches the renter.
        MaintenanceTicketDTO pmView = tickets.getTicket(id, staff.getId());
        assertThat(pmView.isOtpLocked()).isTrue();
        assertThat(pmView.isClosableWithoutOtp()).isFalse();
        as(renterUser);
        MaintenanceTicketDTO renterView = tickets.getTicket(id, renterUser.getId());
        assertThat(renterView.isOtpLocked()).isFalse();
        assertThat(renterView.isClosableWithoutOtp()).isFalse();
        as(staff);

        // A property manager cannot close it through the status route; a tenant admin can, on the record.
        assertThatThrownBy(() -> tickets.updateStatus(id, "CLOSED", staff.getId()))
                .isInstanceOf(com.datagami.rentaxis.api.exception.AccessDeniedException.class);
        assertThat(status(id)).isEqualTo("RESOLVED");
        User admin = user(UserRole.TENANT_ADMIN);
        as(admin);
        MaintenanceTicketDTO adminView = tickets.getTicket(id, admin.getId());
        assertThat(adminView.isOtpLocked()).isTrue();
        assertThat(adminView.isClosableWithoutOtp()).isTrue();
        assertThat(tickets.updateStatus(id, "CLOSED", admin.getId()).getStatus()).isEqualTo("CLOSED");
        assertThat(historyNotes(id)).last()
                .isEqualTo("Ticket closed without OTP (OTP closure locked after 10 wrong OTPs)");
    }
}
