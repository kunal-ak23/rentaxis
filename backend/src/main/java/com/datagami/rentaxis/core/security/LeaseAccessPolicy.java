package com.datagami.rentaxis.core.security;

import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Object-level authorization for leases.
 *
 * <p>Role checks in {@code @PreAuthorize} answer "may this kind of user call
 * this endpoint". They do not answer "may <em>this</em> user see <em>this</em>
 * lease", and for the lease domain that second question was never asked:</p>
 *
 * <ul>
 *   <li>A PROPERTY_MANAGER deliberately assigned to one building could
 *       {@code GET /api/v1/leases} and receive every lease in the organisation
 *       — renter names, rent amounts, Ejari numbers, settlement data — for
 *       properties they were explicitly not given. {@code PropertyService}
 *       already enforces exactly this restriction on properties, so the limit
 *       visible in the UI was not a limit on the API.</li>
 *   <li>A RENTER holding any lease id could list and download that lease's
 *       attachments: contracts, ID scans, cheque images. Someone else's.</li>
 * </ul>
 *
 * <p>The assignment logic previously lived inline in {@code PropertyService}
 * and had exactly two call sites, both in that one file — which is why nothing
 * else in the codebase was scoped. It lives here now so the next domain that
 * needs it does not reimplement it.</p>
 *
 * <p>Deliberately fails closed. A caller whose role is unrecognised, or a
 * renter with no renter record, sees nothing rather than everything.</p>
 */
@Component
public class LeaseAccessPolicy {

    private final UserPropertyAssignmentRepository propertyAssignmentRepository;
    private final RenterRepository renterRepository;

    public LeaseAccessPolicy(UserPropertyAssignmentRepository propertyAssignmentRepository,
            RenterRepository renterRepository) {
        this.propertyAssignmentRepository = propertyAssignmentRepository;
        this.renterRepository = renterRepository;
    }

    /** Narrows a tenant-scoped list to what the caller is actually allowed to see. */
    public List<Lease> filterReadable(List<Lease> leases) {
        Caller caller = currentCaller();
        if (caller.seesEverything()) {
            return leases;
        }
        return leases.stream().filter(l -> canRead(l, caller)).toList();
    }

    /**
     * Guard for a single lease.
     *
     * <p>Throws {@link NotFoundException}, not an access-denied error, and that
     * is the point: a 403 on a lease id confirms the lease exists, which lets a
     * caller enumerate the tenant's leases through the error code alone. To
     * someone not entitled to it, it is simply not there.</p>
     */
    public void requireReadable(Lease lease) {
        if (lease == null) {
            throw new NotFoundException("Lease not found");
        }
        Caller caller = currentCaller();
        if (!caller.seesEverything() && !canRead(lease, caller)) {
            throw new NotFoundException("Lease not found");
        }
    }

    /**
     * Whether a real, authenticated principal is on the SecurityContext.
     *
     * <p>Spring Security's {@code AnonymousAuthenticationFilter} installs an
     * {@link AnonymousAuthenticationToken} on every request that carries no
     * credentials, so {@code getAuthentication() != null} is TRUE even for the
     * gateway webhook, which is unauthenticated by design and vouched for by its
     * signature instead. A caller asking "is there a user here to authorise?"
     * must ask this, not the context directly — reading the context naively
     * makes the webhook look like a logged-in stranger, and the lease guard
     * answers "Lease not found" for a payment the renter has already made.</p>
     */
    public boolean hasAuthenticatedCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

    /**
     * Whether the caller is scoped to a subset at all.
     *
     * <p>Lets a caller keep database-side pagination for the unrestricted case
     * instead of loading every lease just to discover nothing needed filtering.
     * Resolving this costs one assignment or renter lookup, not a lease scan.</p>
     */
    public boolean isRestricted() {
        return !currentCaller().seesEverything();
    }

    public boolean canRead(Lease lease) {
        Caller caller = currentCaller();
        return caller.seesEverything() || canRead(lease, caller);
    }

    /**
     * The properties a restricted caller may see, or {@code null} when they see
     * everything.
     *
     * <p>{@link #filterReadable} and {@link #canRead} answer per lease, which is
     * the wrong shape for a <em>paged</em> list hanging off a property: filtering
     * a page after the database produced it reports a total that counts rows the
     * caller may not see and hands back short pages. So a caller that pages by
     * property pushes this into its own query instead.</p>
     *
     * <p>Fails closed exactly as {@link #canRead} does: a renter, a tenant user or
     * an unauthenticated caller gets an empty list, which selects nothing — not
     * an absent restriction, which would select everything.</p>
     */
    public List<UUID> visiblePropertyIds() {
        Caller caller = currentCaller();
        if (caller.seesEverything()) {
            return null;
        }
        return caller.isPropertyManager() ? caller.assignedPropertyIds() : List.of();
    }

    /**
     * Guard for <em>changing</em> a lease or anything hanging off it — a cheque
     * moving through the register, a termination, a settlement.
     *
     * <p>Reading and writing are different questions and were being answered by
     * one method. A renter passes {@link #requireReadable} for their own lease,
     * which is correct: it is their tenancy contract. It is emphatically not a
     * licence to mark their own cheque cleared. So finance actions ask this
     * instead, and the split is: tenant-wide roles pass, a property manager
     * passes for the buildings they were actually assigned — exactly the set
     * {@link #canRead} gives them, since a manager who may see a lease is a
     * manager who may run its collections — and renters and tenant users are
     * refused outright.</p>
     *
     * <p>{@link NotFoundException} again rather than access-denied, for the same
     * reason: a 403 on a lease id confirms the lease exists.</p>
     */
    public void requireManageable(Lease lease) {
        if (!canManage(lease)) {
            throw new NotFoundException("Lease not found");
        }
    }

    public boolean canManage(Lease lease) {
        return canManage(lease, currentCaller());
    }

    private boolean canManage(Lease lease, Caller caller) {
        if (lease == null) {
            return false;
        }
        if (caller.seesEverything()) {
            return true;
        }
        if (caller.isPropertyManager()) {
            return canRead(lease, caller);
        }
        // Renters, tenant users, unrecognised roles and unauthenticated callers:
        // they may be entitled to look at the contract, never to move its money.
        return false;
    }

    private boolean canRead(Lease lease, Caller caller) {
        if (lease == null) {
            return false;
        }
        if (caller.isPropertyManager()) {
            UUID propertyId = lease.getUnit() != null && lease.getUnit().getProperty() != null
                    ? lease.getUnit().getProperty().getId()
                    : null;
            return propertyId != null && caller.assignedPropertyIds().contains(propertyId);
        }
        if (caller.isRenter()) {
            UUID renterId = lease.getRenter() != null ? lease.getRenter().getId() : null;
            return renterId != null && renterId.equals(caller.renterId());
        }
        // Unrecognised role, or no authentication at all: fail closed.
        return false;
    }

    private Caller currentCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Caller.nobody();
        }

        List<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        if (roles.contains("ROLE_SUPER_ADMIN") || roles.contains("ROLE_TENANT_ADMIN")
                || roles.contains("ROLE_ACCOUNTANT")) {
            // Tenant-wide by design; the Hibernate tenant filter is the boundary.
            //
            // ACCOUNTANT is here because the role is tenant-wide finance access,
            // not a property assignment: it already reads every property, unit and
            // renter (plan 1), and a lease is the source document behind the
            // journals it reconciles. Without this it fell through to "nobody" and
            // the cheque-grid endpoints it is explicitly granted answered "Lease
            // not found" for every lease in the organisation.
            return Caller.seesAll();
        }

        UUID userId = principalId(auth);
        if (userId == null) {
            return Caller.nobody();
        }

        if (roles.contains("ROLE_PROPERTY_MANAGER")) {
            List<UUID> assigned = propertyAssignmentRepository.findByUserId(userId).stream()
                    .map(a -> a.getPropertyId())
                    .toList();
            return Caller.forPropertyManager(assigned);
        }

        if (roles.contains("ROLE_RENTER") || roles.contains("ROLE_TENANT_USER")) {
            Optional<Renter> renter = renterRepository.findByUserId(userId);
            // No renter record means no lease can match: fail closed rather than
            // falling through to "sees everything".
            return Caller.forRenter(renter.map(Renter::getId).orElse(null));
        }

        return Caller.nobody();
    }

    private UUID principalId(Authentication auth) {
        Object principal = auth.getPrincipal();
        if (principal == null) {
            return null;
        }
        try {
            return UUID.fromString(principal.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Snapshot of who is asking, resolved once per call rather than per lease. */
    private record Caller(boolean unrestricted, List<UUID> assignedPropertyIds, UUID renterId,
                          boolean propertyManager, boolean renter) {

        static Caller seesAll() {
            return new Caller(true, List.of(), null, false, false);
        }

        static Caller forPropertyManager(List<UUID> assigned) {
            return new Caller(false, assigned, null, true, false);
        }

        static Caller forRenter(UUID renterId) {
            return new Caller(false, List.of(), renterId, false, true);
        }

        static Caller nobody() {
            return new Caller(false, List.of(), null, false, false);
        }

        boolean seesEverything() {
            return unrestricted;
        }

        boolean isPropertyManager() {
            return propertyManager;
        }

        boolean isRenter() {
            return renter;
        }
    }
}
