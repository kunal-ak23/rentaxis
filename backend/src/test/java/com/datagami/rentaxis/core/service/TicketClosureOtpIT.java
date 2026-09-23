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
}
