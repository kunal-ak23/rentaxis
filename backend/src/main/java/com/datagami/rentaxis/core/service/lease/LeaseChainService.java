package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.lease.LeaseChainLinkDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * #100-e: a lease's whole history — every renewal and every unit transfer, in
 * order (spec §2, "the lease page's History strip lists the chain in order").
 *
 * <p><b>Walked by the links, not by {@code chain_id}.</b> {@code chain_id} is the
 * same for the whole chain, but a lease written before the column existed has none,
 * and the links ({@code renewed_from_lease_id}, {@code transferred_from_lease_id})
 * are what every other reader of the chain trusts. The walk goes back through the
 * predecessors and forward through every successor, with a visited set and a depth
 * cap so a cycle written by a bad migration cannot hang the request.</p>
 *
 * <p><b>Access:</b> the lease asked about must be readable, exactly as
 * {@code GET /leases/{id}}. Every other contract in the chain is passed through the
 * same {@link LeaseAccessPolicy}; a contract on a building the caller may not read
 * is dropped, not masked, so the strip never confirms that a lease exists elsewhere.</p>
 *
 * <p>{@code @Transactional}: the loads are JPQL and rely on the Hibernate tenant
 * filter, which {@code TenantAspect} enables only inside a transaction, and the DTOs
 * read each lease's unit and property, which must happen before the session closes
 * (OSIV is off).</p>
 */
@Service
public class LeaseChainService {

    private static final int MAX_CHAIN = 100;

    private final LeaseRepository leaseRepository;
    private final LeaseAccessPolicy leaseAccessPolicy;

    public LeaseChainService(LeaseRepository leaseRepository, LeaseAccessPolicy leaseAccessPolicy) {
        this.leaseRepository = leaseRepository;
        this.leaseAccessPolicy = leaseAccessPolicy;
    }

    @Transactional(readOnly = true)
    public List<LeaseChainLinkDTO> chain(UUID leaseId) {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant in context; a lease's history cannot be read without one");
        }
        Lease asked = leaseRepository.findByIdScopedToTenant(leaseId)
                .filter(l -> tenantId.equals(l.getTenantId()))
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        leaseAccessPolicy.requireReadable(asked);

        // Back to the head of the chain.
        Lease head = asked;
        Set<UUID> seen = new HashSet<>();
        seen.add(head.getId());
        for (int i = 0; i < MAX_CHAIN && head.predecessorId() != null; i++) {
            Lease previous = leaseRepository.findByIdScopedToTenant(head.predecessorId())
                    .filter(l -> tenantId.equals(l.getTenantId())).orElse(null);
            if (previous == null || !seen.add(previous.getId())) break;
            head = previous;
        }

        // Then forward through every successor (renewal or transfer) from the head.
        List<Lease> chain = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        Deque<Lease> queue = new ArrayDeque<>();
        queue.add(head);
        while (!queue.isEmpty() && chain.size() < MAX_CHAIN) {
            Lease lease = queue.poll();
            if (!visited.add(lease.getId())) continue;
            chain.add(lease);
            List<Lease> next = new ArrayList<>(leaseRepository.findByRenewedFromLeaseId(lease.getId()));
            next.addAll(leaseRepository.findByTransferredFromLeaseId(lease.getId()));
            for (Lease s : next) {
                if (tenantId.equals(s.getTenantId())) queue.add(s);
            }
        }

        return leaseAccessPolicy.filterReadable(chain).stream()
                .sorted(Comparator.comparing(Lease::getStartDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Lease::getId))
                .map(LeaseChainService::toDto)
                .toList();
    }

    private static LeaseChainLinkDTO toDto(Lease l) {
        String link = l.getRenewedFromLeaseId() != null ? LeaseChainLinkDTO.RENEWED
                : l.getTransferredFromLeaseId() != null ? LeaseChainLinkDTO.TRANSFERRED : null;
        String unit = l.getUnit() != null ? l.getUnit().getUnitNumber() : null;
        String propertyCode = l.getUnit() != null && l.getUnit().getProperty() != null
                ? l.getUnit().getProperty().getCode() : null;
        return new LeaseChainLinkDTO(l.getId(), unit,
                LeaseService.displayContractNumber(propertyCode, l.getContractNumber()),
                l.getStartDate(), l.getEndDate(), l.getStatus(), link);
    }
}
