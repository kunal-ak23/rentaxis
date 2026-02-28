package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseEvent;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeaseExpirationJob {

    private final LeaseRepository leaseRepository;
    private final LeaseEventRepository leaseEventRepository;

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
