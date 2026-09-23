package com.datagami.rentaxis.core.security;

import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Unit;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The one place that answers "may this PROPERTY_MANAGER touch this property?".
 *
 * <p>A property manager is assigned to specific buildings. Leases and cheques
 * already honoured that through {@link LeaseAccessPolicy}; tickets, gate passes,
 * walk-ins, meetings, listings, units, buildings, contacts, lease interactions and
 * attachments did not, each checking only the tenant (audit P1-3..P1-6, #72). Four
 * controllers had grown their own private copy of the check. They all route here
 * now, so the next property-bound endpoint has one thing to call and
 * {@code PropertyScopeCoverageTest} has one thing to look for.</p>
 *
 * <p>Scope applies to PROPERTY_MANAGER only. Tenant-wide roles (SUPER_ADMIN,
 * TENANT_ADMIN, ACCOUNTANT) pass, and the remaining roles (RENTER, TENANT_USER,
 * SECURITY_GUARD) are governed by their own ownership / posting rules at each
 * endpoint, which this helper deliberately leaves alone: it narrows managers, it
 * never widens anybody.</p>
 *
 * <p>Out of scope reads as {@link NotFoundException}: a 403 on an id would confirm
 * that the object exists in another manager's building.</p>
 */
@Component
public class PropertyScope {

    private final LeaseAccessPolicy leaseAccessPolicy;

    public PropertyScope(LeaseAccessPolicy leaseAccessPolicy) {
        this.leaseAccessPolicy = leaseAccessPolicy;
    }

    /** Whether the caller is a PROPERTY_MANAGER, i.e. limited to assigned properties. */
    public boolean isScoped() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_PROPERTY_MANAGER".equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The properties a manager is assigned to, or {@code null} when the caller is
     * not property-scoped. An empty list means "assigned to nothing", which selects
     * nothing; never treat it as "no restriction".
     */
    public List<UUID> scopedPropertyIds() {
        if (!isScoped()) {
            return null;
        }
        List<UUID> ids = leaseAccessPolicy.visiblePropertyIds();
        return ids == null ? List.of() : ids.stream().distinct().toList();
    }

    public boolean canAccessProperty(UUID propertyId) {
        List<UUID> scoped = scopedPropertyIds();
        return scoped == null || (propertyId != null && scoped.contains(propertyId));
    }

    /** 404 "Property not found" unless the caller may act on this property. */
    public void requireCanAccessProperty(UUID propertyId) {
        requireCanAccessProperty(propertyId, "Property not found");
    }

    /** As {@link #requireCanAccessProperty(UUID)}, with the 404 naming the object asked for. */
    public void requireCanAccessProperty(UUID propertyId, String notFoundMessage) {
        if (!canAccessProperty(propertyId)) {
            throw new NotFoundException(notFoundMessage);
        }
    }

    public void requireCanAccessUnit(Unit unit, String notFoundMessage) {
        UUID propertyId = unit != null && unit.getProperty() != null ? unit.getProperty().getId() : null;
        if (unit == null || (isScoped() && !canAccessProperty(propertyId))) {
            throw new NotFoundException(notFoundMessage);
        }
    }

    /**
     * For a manager: the lease's property must be assigned. Other roles keep the
     * lease rules they already had (use {@link LeaseAccessPolicy} for renters).
     */
    public void requireCanAccessLease(Lease lease) {
        if (lease == null) {
            throw new NotFoundException("Lease not found");
        }
        if (isScoped()) {
            leaseAccessPolicy.requireManageable(lease);
        }
    }

    /**
     * For a manager with no property given, where the endpoint cannot be answered
     * tenant-wide: 400-style refusal, kept from the controllers this replaced.
     */
    public void requirePropertyNamedByManager(UUID propertyId) {
        if (propertyId == null && isScoped()) {
            throw new AccessDeniedException("propertyId is required for property managers");
        }
    }

    /** Narrows a list to the rows on the caller's properties (unchanged for unscoped callers). */
    public <T> List<T> filter(Collection<T> rows, Function<T, UUID> propertyOf) {
        List<UUID> scoped = scopedPropertyIds();
        if (scoped == null) {
            return rows instanceof List<T> l ? l : List.copyOf(rows);
        }
        return rows.stream().filter(r -> {
            UUID p = propertyOf.apply(r);
            return p != null && scoped.contains(p);
        }).toList();
    }
}
