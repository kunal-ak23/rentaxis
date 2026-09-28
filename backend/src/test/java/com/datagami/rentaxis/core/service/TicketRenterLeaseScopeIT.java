package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateTicketDTO;
import com.datagami.rentaxis.api.dto.MaintenanceTicketDTO;
import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Break-it R3 portal3 F1 (P0): a renter raises tickets only on one of their own
 * current contracts (live, today inside the term), and the ticket always carries
 * that lease — its renter is the closure-OTP holder. Staff are unchanged.
 */
@SpringBootTest
class TicketRenterLeaseScopeIT extends AbstractPostgresIT {

    @Autowired MaintenanceTicketService tickets;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired JdbcTemplate jdbc;

    private UUID tenantId;
    private Property sweep;
    private Property other;
    private Unit unitA;
    private Unit unitC;
    private Unit unitOther;
    private User userA;
    private User userC;
    private Lease leaseA;
    private Lease leaseC;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("TRLS-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);

        sweep = property("Sweep House");
        other = property("PropB");
        unitA = unit(sweep, "A-101");
        unitC = unit(sweep, "C-202");
        unitOther = unit(other, "B-1");

        userA = user(UserRole.RENTER);
        userC = user(UserRole.RENTER);
        leaseA = lease(renter(userA), unitA, LeaseStatus.ACTIVE, today.minusMonths(2), today.plusMonths(10));
        leaseC = lease(renter(userC), unitC, LeaseStatus.ACTIVE, today.minusMonths(2), today.plusMonths(10));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    // ---- fixtures ----

    private Property property(String name) {
        Property p = new Property();
        p.setNameEn(name + " " + UUID.randomUUID());
        p.setEmirate(Emirate.DUBAI);
        p.setTenantId(tenantId);
        return propertyRepo.save(p);
    }

    private Unit unit(Property p, String number) {
        Unit u = new Unit();
        u.setProperty(p);
        u.setUnitNumber(number);
        u.setTenantId(tenantId);
        return unitRepo.save(u);
    }

    private User user(UserRole role) {
        User u = new User();
        u.setEmail("trls-" + UUID.randomUUID() + "@t.io");
        u.setName(role.name());
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    private Renter renter(User u) {
        Renter r = new Renter();
        r.setNameEn("Renter " + u.getId());
        r.setUserId(u.getId());
        r.setTenantId(tenantId);
        return renterRepo.save(r);
    }

    private Lease lease(Renter r, Unit u, LeaseStatus status, LocalDate start, LocalDate end) {
        Lease l = new Lease();
        l.setUnit(u);
        l.setRenter(r);
        l.setTenantId(tenantId);
        l.setStartDate(start);
        l.setEndDate(end);
        l.setRentAmount(BigDecimal.valueOf(60000));
        l.setStatus(status);
        return leaseRepo.save(l);
    }

    private static void as(User u, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                u.getId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private static CreateTicketDTO dto(Property p, Unit u, Lease l) {
        CreateTicketDTO dto = new CreateTicketDTO();
        dto.setTitle("Leak");
        dto.setPriority("URGENT");
        dto.setPropertyId(p.getId());
        if (u != null) dto.setUnitId(u.getId());
        if (l != null) dto.setLeaseId(l.getId());
        return dto;
    }

    private UUID storedLease(UUID ticketId) {
        return jdbc.queryForObject("SELECT lease_id FROM maintenance_tickets WHERE id = ?", UUID.class, ticketId);
    }

    private int ticketCount() {
        return jdbc.queryForObject("SELECT count(*) FROM maintenance_tickets WHERE tenant_id = ?", Integer.class, tenantId);
    }

    // ---- the attack ----

    @Test
    void aRenterCannotRaiseATicketOnANeighboursUnit() {
        as(userA, "RENTER");
        assertThatThrownBy(() -> tickets.createTicket(dto(sweep, unitC, null), userA.getId()))
                .isInstanceOf(NotFoundException.class);
        // Naming the neighbour's lease is no way round it either.
        assertThatThrownBy(() -> tickets.createTicket(dto(sweep, unitC, leaseC), userA.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> tickets.createTicket(dto(sweep, null, leaseC), userA.getId()))
                .isInstanceOf(NotFoundException.class);
        // Own lease, someone else's unit.
        assertThatThrownBy(() -> tickets.createTicket(dto(sweep, unitC, leaseA), userA.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThat(ticketCount()).isZero();
    }

    @Test
    void aRenterCannotRaiseATicketOnAPropertyTheyHoldNothingIn() {
        as(userA, "RENTER");
        assertThatThrownBy(() -> tickets.createTicket(dto(other, null, null), userA.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> tickets.createTicket(dto(other, unitOther, null), userA.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThat(ticketCount()).isZero();
    }

    @Test
    void aRenterWithNoCurrentContractCannotRaiseTickets() {
        User ended = user(UserRole.RENTER);
        Renter endedRenter = renter(ended);
        Unit endedUnit = unit(other, "E-1");
        // Still ACTIVE, but the term is over (no expiry job ran).
        lease(endedRenter, endedUnit, LeaseStatus.ACTIVE, today.minusYears(1), today.minusDays(40));
        User terminated = user(UserRole.RENTER);
        Unit terminatedUnit = unit(other, "T-1");
        lease(renter(terminated), terminatedUnit, LeaseStatus.TERMINATED, today.minusMonths(3), today.plusMonths(9));
        User future = user(UserRole.RENTER);
        Unit futureUnit = unit(other, "F-1");
        lease(renter(future), futureUnit, LeaseStatus.ACTIVE, today.plusDays(10), today.plusYears(1));

        as(ended, "RENTER");
        assertThatThrownBy(() -> tickets.createTicket(dto(other, endedUnit, null), ended.getId()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage(MaintenanceTicketService.NO_CURRENT_CONTRACT);
        as(terminated, "RENTER");
        assertThatThrownBy(() -> tickets.createTicket(dto(other, terminatedUnit, null), terminated.getId()))
                .isInstanceOf(AccessDeniedException.class);
        as(future, "RENTER");
        assertThatThrownBy(() -> tickets.createTicket(dto(other, futureUnit, null), future.getId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(ticketCount()).isZero();
    }

    // ---- what still works ----

    @Test
    void aRenterRaisesATicketOnTheirOwnUnitAndItCarriesTheirLease() {
        as(userA, "RENTER");
        // The web and mobile send the unit (and property) only.
        UUID byUnit = tickets.createTicket(dto(sweep, unitA, null), userA.getId()).getId();
        assertThat(storedLease(byUnit)).isEqualTo(leaseA.getId());
        // With the lease named.
        UUID byLease = tickets.createTicket(dto(sweep, unitA, leaseA), userA.getId()).getId();
        assertThat(storedLease(byLease)).isEqualTo(leaseA.getId());
        // Property only: their one contract there is the ticket's, unit included.
        MaintenanceTicketDTO byProperty = tickets.createTicket(dto(sweep, null, null), userA.getId());
        assertThat(storedLease(byProperty.getId())).isEqualTo(leaseA.getId());
        assertThat(byProperty.getUnitId()).isEqualTo(unitA.getId());
        // And they hold the closure OTP of their own ticket.
        assertThat(tickets.getTicket(byUnit, userA.getId())).isNotNull();
    }

    @Test
    void aRenterOnNoticeStillRaisesTickets() {
        leaseA.setStatus(LeaseStatus.NOTICE_GIVEN);
        leaseRepo.save(leaseA);
        as(userA, "RENTER");
        UUID id = tickets.createTicket(dto(sweep, unitA, null), userA.getId()).getId();
        assertThat(storedLease(id)).isEqualTo(leaseA.getId());
    }

    @Test
    void twoContractsInOneBuildingNeedTheUnitNamed() {
        Unit second = unit(sweep, "A-102");
        Lease leaseA2 = lease(renterRepo.findByUserId(userA.getId()).orElseThrow(), second, LeaseStatus.ACTIVE,
                today.minusMonths(1), today.plusMonths(11));
        as(userA, "RENTER");
        assertThatThrownBy(() -> tickets.createTicket(dto(sweep, null, null), userA.getId()))
                .isInstanceOf(BusinessRuleViolationException.class);
        UUID id = tickets.createTicket(dto(sweep, second, null), userA.getId()).getId();
        assertThat(storedLease(id)).isEqualTo(leaseA2.getId());
    }

    @Test
    void staffStillRaiseTicketsOnAnyUnitTheyManage() {
        User admin = user(UserRole.TENANT_ADMIN);
        as(admin, "TENANT_ADMIN");
        UUID id = tickets.createTicket(dto(sweep, unitC, null), admin.getId()).getId();
        assertThat(storedLease(id)).isNull();
        UUID onOther = tickets.createTicket(dto(other, null, null), admin.getId()).getId();
        assertThat(onOther).isNotNull();
    }
}
