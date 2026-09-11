package com.datagami.rentaxis.core.security;

import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Object-level authorization for leases.
 *
 * <p>The role annotations on these endpoints answer "may this kind of user call
 * this", never "may this user see this lease". Most of what follows is negative
 * cases, because that is where the defect lived: a PROPERTY_MANAGER assigned to
 * one building could read every lease in the organisation, and a RENTER holding
 * any lease id could read that lease's attachments.</p>
 */
class LeaseAccessPolicyTest {

    private UserPropertyAssignmentRepository assignmentRepository;
    private RenterRepository renterRepository;
    private LeaseAccessPolicy policy;

    private final UUID userId = UUID.randomUUID();
    private final UUID assignedPropertyId = UUID.randomUUID();
    private final UUID otherPropertyId = UUID.randomUUID();
    private final UUID myRenterId = UUID.randomUUID();
    private final UUID otherRenterId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        assignmentRepository = mock(UserPropertyAssignmentRepository.class);
        renterRepository = mock(RenterRepository.class);
        policy = new LeaseAccessPolicy(assignmentRepository, renterRepository);

        when(assignmentRepository.findByUserId(any())).thenReturn(List.of());
        when(renterRepository.findByUserId(any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId.toString(), "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private void assignedTo(UUID propertyId) {
        UserPropertyAssignment a = new UserPropertyAssignment();
        a.setUserId(userId);
        a.setPropertyId(propertyId);
        when(assignmentRepository.findByUserId(userId)).thenReturn(List.of(a));
    }

    private void renterRecord(UUID renterId) {
        Renter r = new Renter();
        r.setId(renterId);
        r.setUserId(userId);
        when(renterRepository.findByUserId(userId)).thenReturn(Optional.of(r));
    }

    private Lease lease(UUID propertyId, UUID renterId) {
        Property property = new Property();
        property.setId(propertyId);

        Unit unit = new Unit();
        unit.setId(UUID.randomUUID());
        unit.setProperty(property);

        Renter renter = new Renter();
        renter.setId(renterId);

        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setUnit(unit);
        lease.setRenter(renter);
        return lease;
    }

    // ---- admins -----------------------------------------------------------

    @Test
    void tenantAdminSeesEveryLeaseInTheTenant() {
        authenticateAs("TENANT_ADMIN");
        List<Lease> all = List.of(lease(assignedPropertyId, myRenterId), lease(otherPropertyId, otherRenterId));

        assertThat(policy.filterReadable(all)).hasSize(2);
        assertThat(policy.isRestricted()).isFalse();
    }

    @Test
    void superAdminSeesEveryLeaseInTheTenant() {
        authenticateAs("SUPER_ADMIN");

        assertThat(policy.filterReadable(List.of(lease(otherPropertyId, otherRenterId)))).hasSize(1);
        assertThat(policy.isRestricted()).isFalse();
    }

    // ---- property managers -------------------------------------------------

    @Test
    void propertyManagerSeesOnlyLeasesOnAssignedProperties() {
        authenticateAs("PROPERTY_MANAGER");
        assignedTo(assignedPropertyId);

        Lease mine = lease(assignedPropertyId, myRenterId);
        Lease theirs = lease(otherPropertyId, otherRenterId);

        assertThat(policy.filterReadable(List.of(mine, theirs)))
                .as("a lease on an unassigned property must not be returned")
                .containsExactly(mine);
        assertThat(policy.isRestricted()).isTrue();
    }

    @Test
    void propertyManagerWithNoAssignmentsSeesNothing() {
        // Fails closed. The tempting bug is to treat "no assignments" as "no
        // filter", which hands them the whole tenant.
        authenticateAs("PROPERTY_MANAGER");

        assertThat(policy.filterReadable(List.of(lease(assignedPropertyId, myRenterId)))).isEmpty();
    }

    @Test
    void propertyManagerIsRefusedALeaseOnAnUnassignedProperty() {
        authenticateAs("PROPERTY_MANAGER");
        assignedTo(assignedPropertyId);

        assertThatThrownBy(() -> policy.requireReadable(lease(otherPropertyId, otherRenterId)))
                .isInstanceOf(NotFoundException.class);
        assertThatCode(() -> policy.requireReadable(lease(assignedPropertyId, myRenterId)))
                .doesNotThrowAnyException();
    }

    // ---- renters -----------------------------------------------------------

    @Test
    void renterSeesOnlyTheirOwnLease() {
        authenticateAs("RENTER");
        renterRecord(myRenterId);

        Lease mine = lease(assignedPropertyId, myRenterId);
        Lease theirs = lease(assignedPropertyId, otherRenterId);

        assertThat(policy.filterReadable(List.of(mine, theirs))).containsExactly(mine);
    }

    /**
     * The reported defect: a renter holding any lease id could list and
     * download that lease's contracts, ID scans and cheque images.
     */
    @Test
    void renterIsRefusedSomeoneElsesLease() {
        authenticateAs("RENTER");
        renterRecord(myRenterId);

        assertThatThrownBy(() -> policy.requireReadable(lease(assignedPropertyId, otherRenterId)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void renterWithNoRenterRecordSeesNothing() {
        authenticateAs("RENTER");

        assertThat(policy.filterReadable(List.of(lease(assignedPropertyId, myRenterId)))).isEmpty();
        assertThatThrownBy(() -> policy.requireReadable(lease(assignedPropertyId, myRenterId)))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * NotFound rather than Forbidden is deliberate: a 403 on a lease id
     * confirms the lease exists, which lets a caller enumerate the tenant's
     * leases through the status code alone.
     */
    @Test
    void refusalDoesNotRevealThatTheLeaseExists() {
        authenticateAs("RENTER");
        renterRecord(myRenterId);

        assertThatThrownBy(() -> policy.requireReadable(lease(assignedPropertyId, otherRenterId)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("not found");
        assertThatThrownBy(() -> policy.requireReadable(null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("not found");
    }

    // ---- fail-closed defaults ----------------------------------------------

    @Test
    void anUnauthenticatedCallerSeesNothing() {
        SecurityContextHolder.clearContext();

        assertThat(policy.filterReadable(List.of(lease(assignedPropertyId, myRenterId)))).isEmpty();
    }

    @Test
    void anUnrecognisedRoleSeesNothing() {
        authenticateAs("SECURITY_GUARD");

        assertThat(policy.filterReadable(List.of(lease(assignedPropertyId, myRenterId)))).isEmpty();
    }

    @Test
    void aLeaseWithNoUnitIsNotReadableByAManager() {
        // Defensive: a malformed lease must not become universally readable.
        authenticateAs("PROPERTY_MANAGER");
        assignedTo(assignedPropertyId);

        Lease orphan = new Lease();
        orphan.setId(UUID.randomUUID());

        assertThat(policy.filterReadable(List.of(orphan))).isEmpty();
    }
}
