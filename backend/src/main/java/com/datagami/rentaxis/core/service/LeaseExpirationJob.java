package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseEvent;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class LeaseExpirationJob {

    private final LeaseRepository leaseRepository;
    private final LeaseEventRepository leaseEventRepository;
    private final UnitRepository unitRepository;
    private final PaymentScheduleRepository paymentScheduleRepository;

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

            // Cancel what will never be collected, as terminateLease does.
            // Leaving these PENDING kept an expired lease's installments in the
            // aging report as live arrears against a renter who has gone.
            for (PaymentSchedule ps : paymentScheduleRepository.findByLeaseId(lease.getId())) {
                if (ps.getStatus() == PaymentStatus.PENDING
                        || ps.getStatus() == PaymentStatus.ONLINE_PENDING
                        || ps.getStatus() == PaymentStatus.OVERDUE) {
                    ps.setStatus(PaymentStatus.CANCELLED);
                    paymentScheduleRepository.save(ps);
                }
            }

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
