package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Component
@Slf4j
public class ChequeImageRetentionJob {

    private final PaymentScheduleRepository repo;
    private final BlobStorageService blob;

    @Value("${cheque-extraction.retention-days:90}")
    private int retentionDays;

    public ChequeImageRetentionJob(PaymentScheduleRepository repo, BlobStorageService blob) {
        this.repo = repo;
        this.blob = blob;
    }

    @Scheduled(cron = "${cheque-extraction.purge-cron:0 0 3 * * *}")
    @Transactional
    public void purge() {
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
        var rows = repo.findChequeImagesOlderThan(cutoff);
        int ok = 0;
        int failed = 0;

        for (var row : rows) {
            try {
                blob.delete(row.tenantId(), row.chequeImageBlobPath());
                repo.clearChequeImage(row.id());
                ok++;
            } catch (Exception e) {
                failed++;
                log.warn("Failed to purge cheque image {}: {}", row.id(), e.getMessage());
            }
        }

        log.info("Cheque retention purge: cutoff={}, deleted={}, failed={}", cutoff, ok, failed);
    }
}
