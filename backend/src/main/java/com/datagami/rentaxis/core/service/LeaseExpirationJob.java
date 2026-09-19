package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseEvent;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A tenancy whose end date has passed becomes EXPIRED, and its unit becomes
 * available again.
 *
 * <p><b>It does not touch the cheque register.</b> The job used to cancel every
 * uncollected instalment on the way past, and that was wrong in the one direction
 * that costs the landlord money: a held, undeposited cheque for the final month
 * is still an instrument against a debt the renter genuinely owes, and a lease
 * reaching its end date does not settle it. Deciding what happens to uncleared
 * paper — returned, banked, or carried into a renewal — is the termination and
 * settlement flow's job, where a human is looking at the contract. Expiry is a
 * calendar fact and posts nothing.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaseExpirationJob {

    private final LeaseRepository leaseRepository;
    private final LeaseEventRepository leaseEventRepository;
    private final UnitRepository unitRepository;

    @Scheduled(cron = "0 0 0 * * ?") // Run at midnight every day
    @Transactional
    public void evaluateExpiredLeases() {
        log.info("Starting evaluation of expired leases");
        LocalDate today = LocalDate.now();

        List<Lease> expiredLeases = leaseRepository.findByStatusInAndEndDateBefore(
                List.of(LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN), today);

        for (Lease lease : expiredLeases) {
            LeaseStatus prev = lease.getStatus();
            lease.setStatus(LeaseStatus.EXPIRED);

            // Expiry and termination are the two ways out of an ACTIVE lease and
            // must leave the unit in the same state. This job used to set only
            // the status, so the unit went VACANT while still advertising the
            // departed renter's name and their rent as its actual_rent —
            // skewing occupancy and revenue reporting until someone noticed.
            // Changeset 68's second UPDATE is exactly this cleanup, run as a
            // production backfill, and 70 describes the same drift.
            if (lease.getUnit() != null) {
                Unit unit = lease.getUnit();
                unit.setStatus(UnitStatus.VACANT);
                unit.setCurrentTenantName(null);
                unit.setActualRent(BigDecimal.ZERO);
                unitRepository.save(unit);
            }

            // The register is deliberately left alone — see the class note.
            leaseRepository.save(lease);

            LeaseEvent event = new LeaseEvent();
            event.setLease(lease);
            event.setTenantId(lease.getTenantId());
            event.setPreviousState(prev);
            event.setNewState(LeaseStatus.EXPIRED);
            event.setNotes("Automatically transitioned to EXPIRED by system job");
            event.setCreatedAt(Instant.now());
            leaseEventRepository.save(event);

            log.info("Lease {} marked as EXPIRED", lease.getId());
        }

        log.info("Finished evaluation of expired leases. Processed {} leases", expiredLeases.size());
    }
}
