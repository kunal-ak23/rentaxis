package com.datagami.rentaxis.core.tenant;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.MaintenanceTicket;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.TicketCategory;
import com.datagami.rentaxis.domain.entity.enums.TicketPriority;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.MaintenanceTicketRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link TenantAspect} actually covers.
 *
 * <p>The aspect enables Hibernate's {@code tenantFilter} before calls into
 * {@code com.datagami.rentaxis.domain.repository..*}. Every service in this
 * codebase leans on that: a repository read with no explicit tenant comparison is
 * safe <em>only</em> if the filter is on. The open question this class answers by
 * running it is whether the pointcut fires for the methods nobody declares —
 * {@code findAll}, {@code count}, {@code findById}, {@code existsById},
 * {@code findAll(Pageable)} — which Spring Data inherits from
 * {@code CrudRepository}/{@code JpaRepository} rather than from our interfaces.
 * Reading the expression cannot settle it; a transaction whose only database
 * access is one of those calls can.</p>
 *
 * <p>Deliberately free of any accounting-v2 type: tickets, properties, units,
 * renters and leases only, so this class can be cherry-picked onto any branch.</p>
 */
@SpringBootTest
class TenantAspectIT extends AbstractPostgresIT {

    @Autowired MaintenanceTicketRepository tickets;
    @Autowired LeaseRepository leases;
    @Autowired PropertyRepository properties;
    @Autowired UnitRepository units;
    @Autowired RenterRepository renters;
    @Autowired LandlordOrgRepository orgs;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    UUID tenantA, tenantB;
    UUID ticketA, ticketB, leaseA, leaseB;

    @BeforeEach
    void setUp() {
        tenantA = tenant("A");
        tenantB = tenant("B");
        ticketA = ticketIn(tenantA, "Lift stuck on 12");
        ticketB = ticketIn(tenantB, "Leak in 401");
        leaseA = leaseIn(tenantA);
        leaseB = leaseIn(tenantB);
        TenantContextHolder.clear();
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    private UUID tenant(String label) {
        TenantContextHolder.clear();
        LandlordOrg org = new LandlordOrg();
        org.setName("TA-" + label + "-" + UUID.randomUUID());
        return orgs.save(org).getId();
    }

    private UUID propertyIn(UUID tenantId) {
        TenantContextHolder.setTenantId(tenantId);
        Property p = new Property();
        p.setNameEn("Tower " + UUID.randomUUID());
        p.setEmirate(Emirate.DUBAI);
        return properties.save(p).getId();
    }

    private UUID ticketIn(UUID tenantId, String title) {
        UUID propertyId = propertyIn(tenantId);
        TenantContextHolder.setTenantId(tenantId);
        MaintenanceTicket t = new MaintenanceTicket();
        t.setProperty(properties.findById(propertyId).orElseThrow());
        t.setReportedBy(UUID.randomUUID());
        t.setTitle(title);
        t.setCategory(TicketCategory.PLUMBING);
        t.setPriority(TicketPriority.MEDIUM);
        return tickets.save(t).getId();
    }

    private UUID leaseIn(UUID tenantId) {
        UUID propertyId = propertyIn(tenantId);
        TenantContextHolder.setTenantId(tenantId);
        Unit u = new Unit();
        u.setProperty(properties.findById(propertyId).orElseThrow());
        u.setUnitNumber("U-" + UUID.randomUUID());
        UUID unitId = units.save(u).getId();

        Renter r = new Renter();
        r.setNameEn("Renter " + UUID.randomUUID());
        UUID renterId = renters.save(r).getId();

        Lease l = new Lease();
        l.setUnit(units.findById(unitId).orElseThrow());
        l.setRenter(renters.findById(renterId).orElseThrow());
        l.setStartDate(LocalDate.of(2026, 1, 1));
        l.setEndDate(LocalDate.of(2026, 12, 31));
        l.setStatus(LeaseStatus.ACTIVE);
        l.setRentAmount(new BigDecimal("1200.00"));
        l.setDepositAmount(new BigDecimal("0.00"));
        return leases.save(l).getId();
    }

    /** Runs {@code body} in a fresh transaction with tenant A in context, and nothing else first. */
    private <T> T asTenantAInAFreshTransaction(java.util.function.Supplier<T> body) {
        TenantContextHolder.setTenantId(tenantA);
        return tx.execute(status -> body.get());
    }

    // ---- inherited methods, inside a transaction ----

    @Test
    void findAllInsideATransactionSeesOnlyTheCurrentTenantsRows() {
        List<UUID> ids = asTenantAInAFreshTransaction(
                () -> tickets.findAll().stream().map(MaintenanceTicket::getId).toList());

        assertThat(ids).contains(ticketA).doesNotContain(ticketB);
    }

    @Test
    void countInsideATransactionCountsOnlyTheCurrentTenantsRows() {
        long all = asTenantAInAFreshTransaction(() -> tickets.count());
        assertThat(all).as("tenant A's tickets only").isEqualTo(1);
    }

    /**
     * {@code BaseTenantEntity}'s {@code @FilterDef(applyToLoadByKey = true)} is what
     * makes this hold for a by-key load — but only once the filter is enabled, which
     * is the very thing under test.
     */
    @Test
    void findByIdInsideATransactionDoesNotFindAnotherTenantsRow() {
        boolean foundForeign = asTenantAInAFreshTransaction(() -> tickets.findById(ticketB).isPresent());
        assertThat(foundForeign).as("tenant B's ticket, read by id as tenant A").isFalse();
    }

    @Test
    void existsByIdInsideATransactionIsFalseForAnotherTenantsRow() {
        boolean exists = asTenantAInAFreshTransaction(() -> tickets.existsById(ticketB));
        assertThat(exists).isFalse();
    }

    @Test
    void findAllPageableInsideATransactionSeesOnlyTheCurrentTenantsRows() {
        List<UUID> ids = asTenantAInAFreshTransaction(() -> tickets.findAll(PageRequest.of(0, 50))
                .getContent().stream().map(MaintenanceTicket::getId).toList());

        assertThat(ids).contains(ticketA).doesNotContain(ticketB);
    }

    /** A second repository, so the answer is about the aspect and not about one interface. */
    @Test
    void theSameHoldsForASecondRepository() {
        List<UUID> ids = asTenantAInAFreshTransaction(
                () -> leases.findAll().stream().map(Lease::getId).toList());
        assertThat(ids).contains(leaseA).doesNotContain(leaseB);

        boolean foreign = asTenantAInAFreshTransaction(() -> leases.findById(leaseB).isPresent());
        assertThat(foreign).isFalse();
    }

    /** The mechanism itself: after one inherited call, the session carries the filter. */
    @Test
    void anInheritedCallAloneEnablesTheFilterOnTheSession() {
        Boolean enabled = asTenantAInAFreshTransaction(() -> {
            tickets.count();
            return entityManager.unwrap(Session.class).getEnabledFilter("tenantFilter") != null;
        });
        assertThat(enabled).isTrue();
    }

    // ---- the two documented edges ----

    /**
     * No tenant in context — the SUPER_ADMIN and job-bootstrap paths — must still
     * see everything, or cross-tenant administration stops working.
     */
    @Test
    void withNoTenantInContextNothingIsFiltered() {
        TenantContextHolder.clear();
        List<UUID> ids = tx.execute(s -> tickets.findAll().stream().map(MaintenanceTicket::getId).toList());
        assertThat(ids).contains(ticketA, ticketB);
    }

    /**
     * The known limitation, pinned so it is a decision rather than a surprise: the
     * filter is enabled on the session bound to the <em>current transaction</em>.
     * With no transaction of its own, a repository call gets a fresh session for
     * the query and the filter enabled a moment earlier is not on it. This is why
     * every tenant-scoped service read in this codebase is {@code @Transactional}
     * and why a method without it needs an explicit tenant comparison.
     */
    @Test
    void aReadWithNoTransactionOfItsOwnIsNotFiltered() {
        TenantContextHolder.setTenantId(tenantA);
        List<UUID> ids = tickets.findAll().stream().map(MaintenanceTicket::getId).toList();
        assertThat(ids).as("unfiltered: the documented limitation").contains(ticketA, ticketB);
    }
}
