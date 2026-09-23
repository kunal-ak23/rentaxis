package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.MaintenanceTicketController;
import com.datagami.rentaxis.api.dto.CreateTicketDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.MaintenanceTicket;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.MaintenanceTicketRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR #342 review r3 I2. The closure OTP's failure counters and the code itself
 * live on the ticket row, and every other ticket writer used to load the row
 * with a plain read and save the whole of it back. An estimate that read before
 * a wrong-OTP guess and committed after it wrote the old counters back, so the
 * 5-per-code and 10-per-lifetime limits could be reset at will.
 *
 * <p>The writers now take the row lock before they read, and {@code @Version}
 * refuses any stale write that slips past.</p>
 */
@SpringBootTest
class TicketConcurrentWriteIT extends AbstractPostgresIT {

    @Autowired MaintenanceTicketService tickets;
    @Autowired MaintenanceTicketRepository ticketRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UserRepository userRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager txManager;

    private UUID tenantId;
    private User staff;
    private UUID ticketId;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("CONC-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);

        staff = new User();
        staff.setEmail("conc-" + UUID.randomUUID() + "@t.io");
        staff.setName("PM");
        staff.setRole(UserRole.PROPERTY_MANAGER);
        staff.setStatus(UserStatus.ACTIVE);
        staff.setPasswordHash("x");
        staff.setTenantId(tenantId);
        staff = userRepo.save(staff);

        User renterUser = new User();
        renterUser.setEmail("conc-r-" + UUID.randomUUID() + "@t.io");
        renterUser.setName("Renter");
        renterUser.setRole(UserRole.RENTER);
        renterUser.setStatus(UserStatus.ACTIVE);
        renterUser.setPasswordHash("x");
        renterUser.setTenantId(tenantId);
        renterUser = userRepo.save(renterUser);
        Renter renter = new Renter();
        renter.setNameEn("Renter");
        renter.setUserId(renterUser.getId());
        renter = renterRepo.save(renter);

        Property p = new Property();
        p.setNameEn("Tower " + UUID.randomUUID());
        p.setEmirate(Emirate.DUBAI);
        UUID propertyId = propertyRepo.save(p).getId();
        UserPropertyAssignment a = new UserPropertyAssignment();
        a.setUserId(staff.getId());
        a.setPropertyId(propertyId);
        assignmentRepo.save(a);

        asStaff();
        CreateTicketDTO dto = new CreateTicketDTO();
        dto.setPropertyId(propertyId);
        dto.setTitle("Leaking tap");
        dto.setOnBehalfOfRenterId(renter.getId());
        ticketId = tickets.createTicket(dto, staff.getId()).getId();
        tickets.assignTicket(ticketId, staff.getId(), staff.getId());
        tickets.updateStatus(ticketId, "IN_PROGRESS", staff.getId());
        tickets.updateStatus(ticketId, "RESOLVED", staff.getId());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private void asStaff() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                staff.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_PROPERTY_MANAGER"))));
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT closure_otp, closure_otp_failed_attempts, closure_otp_total_failed_attempts,"
                + " estimated_resolution_hours FROM maintenance_tickets WHERE id = ?", ticketId);
    }

    private boolean someoneWaitsOnALock() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity"
                + " WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
        return n != null && n > 0;
    }

    /**
     * The OTP writer holds the row while the estimate arrives. The estimate must
     * read the row only once the guesses are committed, and keep them.
     */
    @Test
    void anEstimateSavedDuringWrongOtpGuessesCannotResetTheFailureCounters() throws Exception {
        assertThat(row().get("closure_otp")).isNotNull();

        try (Connection guesses = dataSource.getConnection()) {
            guesses.setAutoCommit(false);
            try (PreparedStatement lock = guesses.prepareStatement(
                    "SELECT id FROM maintenance_tickets WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, ticketId);
                lock.executeQuery().close();
            }

            CompletableFuture<Void> estimate = CompletableFuture.runAsync(() -> {
                TenantContextHolder.setTenantId(tenantId);
                asStaff();
                try {
                    tickets.setEstimate(ticketId, 12);
                } finally {
                    TenantContextHolder.clear();
                    SecurityContextHolder.clearContext();
                }
            });

            // Wait until the estimate is parked behind the row lock (at its read
            // with the fix; at its UPDATE without it).
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!someoneWaitsOnALock()) {
                assertThat(System.nanoTime()).as("the estimate never blocked on the ticket row").isLessThan(deadline);
                assertThat(estimate).isNotDone();
                Thread.sleep(25);
            }

            // What closeWithOtp commits at the fifth wrong guess on a code: the
            // counters move, the code is discarded, the version moves on.
            try (PreparedStatement guess = guesses.prepareStatement(
                    "UPDATE maintenance_tickets SET closure_otp_failed_attempts = 5,"
                            + " closure_otp_total_failed_attempts = 7, closure_otp = NULL, version = version + 1"
                            + " WHERE id = ?")) {
                guess.setObject(1, ticketId);
                guess.executeUpdate();
            }
            guesses.commit();

            estimate.get(15, TimeUnit.SECONDS);
        }

        Map<String, Object> after = row();
        assertThat(after.get("estimated_resolution_hours")).isEqualTo(12);
        assertThat(after.get("closure_otp_failed_attempts")).isEqualTo(5);
        assertThat(after.get("closure_otp_total_failed_attempts")).isEqualTo(7);
        assertThat(after.get("closure_otp")).as("a discarded code must not come back").isNull();
    }

    /** The backstop: a save of a row that changed after it was read fails rather than overwriting. */
    @Test
    void aStaleTicketWriteIsRefusedByTheVersionColumn() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            MaintenanceTicket stale = ticketRepo.findById(ticketId).orElseThrow();
            // Committed by someone else after this transaction read the row.
            CompletableFuture.runAsync(() -> jdbc.update(
                    "UPDATE maintenance_tickets SET closure_otp_total_failed_attempts = 3, version = version + 1"
                            + " WHERE id = ?", ticketId)).join();
            stale.setEstimatedResolutionHours(9);
            ticketRepo.save(stale);
        })).isInstanceOf(OptimisticLockingFailureException.class);

        Map<String, Object> after = row();
        assertThat(after.get("closure_otp_total_failed_attempts")).isEqualTo(3);
        assertThat(after.get("estimated_resolution_hours")).isNull();
    }

    @Test
    void aConcurrentChangeIsAConflictForTheClientNotAServerError() {
        var res = new MaintenanceTicketController(tickets).handleConcurrentChange(
                new ObjectOptimisticLockingFailureException(MaintenanceTicket.class, ticketId));
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody()).containsEntry("message",
                "This ticket was changed by someone else at the same time. Reload it and try again.");
    }
}
