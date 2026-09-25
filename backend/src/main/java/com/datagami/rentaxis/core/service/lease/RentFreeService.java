package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.lease.RentFreePeriodDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseRentFreePeriod;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseRentFreePeriodRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Rent-free periods on a draft lease (spec 2026-09-24 §4b, #50; F14-26).
 *
 * <p>The operator enters the headline rent for the whole term and the free
 * windows; the contract's RENT line then carries the concession and the renter
 * pays, the TCO raises and VAT is charged on the rest. Recognition is
 * straight-line over the whole term (product decision): the RENT line keeps the
 * term as its window, so the free months earn income at the same day rate.</p>
 */
@Service
public class RentFreeService {

    private final LeasePostingService posting;
    private final LeaseService leaseService;
    private final LeaseRentFreePeriodRepository periods;
    private final LeaseRepository leases;
    private final ChequeRepository cheques;
    private final LeaseAccessPolicy access;

    public RentFreeService(LeasePostingService posting, LeaseService leaseService,
                           LeaseRentFreePeriodRepository periods, LeaseRepository leases,
                           ChequeRepository cheques, LeaseAccessPolicy access) {
        this.posting = posting;
        this.leaseService = leaseService;
        this.periods = periods;
        this.leases = leases;
        this.cheques = cheques;
        this.access = access;
    }

    /**
     * Replace the draft's rent-free periods. The concession is re-derived on the
     * contract's RENT line and the DRAFT cheque grid is dropped, because it was cut
     * for the old payable rent.
     */
    @Transactional
    public LeaseDTO replace(UUID leaseId, List<RentFreePeriodDTO> input) {
        Lease lease = posting.lockLease(leaseId);
        access.requireManageable(lease);
        if (lease.getStatus() != LeaseStatus.DRAFT) {
            throw new BusinessRuleViolationException(
                    "Rent-free periods can be changed on a draft lease only; this one is " + lease.getStatus() + ".");
        }
        List<LeaseRentFreePeriod> fresh = new ArrayList<>();
        for (RentFreePeriodDTO in : input == null ? List.<RentFreePeriodDTO>of() : input) {
            if (in == null) continue;
            if (in.concessionOverride() != null && in.concessionOverride().signum() < 0) {
                throw new BusinessRuleViolationException("A rent-free concession cannot be negative.");
            }
            LeaseRentFreePeriod p = new LeaseRentFreePeriod();
            p.setTenantId(lease.getTenantId());
            p.setLease(lease);
            p.setFromDate(in.fromDate());
            p.setToDate(in.toDate());
            p.setConcessionOverride(in.concessionOverride());
            p.setNote(in.note() == null || in.note().isBlank() ? null : in.note().trim());
            fresh.add(p);
        }
        LeaseService.requireRentFreePeriodsValid(lease, fresh);

        periods.deleteAll(periods.findByLease_IdOrderByFromDateAsc(leaseId));
        periods.flush();
        periods.saveAll(fresh);
        periods.flush();

        leaseService.applyRentFree(lease);
        leaseService.syncDerivedTotals(lease);
        leases.save(lease);
        cheques.deleteByLease_IdAndStatus(leaseId, ChequeStatus.DRAFT);
        cheques.flush();
        return leaseService.getLeaseById(leaseId);
    }
}
