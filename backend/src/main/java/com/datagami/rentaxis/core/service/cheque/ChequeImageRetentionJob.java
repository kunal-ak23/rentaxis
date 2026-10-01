package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.domain.entity.ChequeImageUpload;
import com.datagami.rentaxis.domain.repository.ChequeImagePurgeRow;
import com.datagami.rentaxis.domain.repository.ChequeImageUploadRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Forgetting the scans. A cheque image is a photograph of somebody's bank
 * account details, and the register has no reason to keep one once the
 * instrument it documents is old enough to be beyond dispute.
 *
 * <p><b>Tenants.</b> Exactly as the job this replaced: it runs with no tenant
 * context, and {@code TenantAspect} only enables the Hibernate tenant filter when
 * there is one, so a single pass covers every organisation. That is deliberate
 * rather than accidental — a retention policy that only ran for whichever tenant
 * happened to be current would silently keep the rest of the estate's scans
 * forever. The blob is deleted against the tenant id carried on each row, which
 * is why the projection includes it.</p>
 *
 * <p><b>Bounded per run.</b> A backlog — the first run after the policy is
 * enabled, or after an outage — can be arbitrarily large, and loading all of it
 * into a scheduled job's heap is how a nightly task takes the application down.
 * One batch is taken per run; the rows it purged no longer match the query, so
 * the next run takes the next batch.</p>
 *
 * <p>A projection, not the entity: the job needs an id and a path, and a managed
 * {@code Cheque} would drag its lease, renter and property behind it for every
 * row of the batch.</p>
 */
@Component
@Slf4j
public class ChequeImageRetentionJob {

    /** Rows per run. Large enough to clear an ordinary day, small enough to bound the heap. */
    private static final int BATCH_SIZE = 500;

    private final ChequeRepository chequeRepository;
    private final BlobStorageService blob;
    private final ChequeImageUploadRepository uploads;

    @Value("${cheque-extraction.retention-days:90}")
    private int retentionDays;

    public ChequeImageRetentionJob(ChequeRepository chequeRepository, BlobStorageService blob,
                                   ChequeImageUploadRepository uploads) {
        this.chequeRepository = chequeRepository;
        this.blob = blob;
        this.uploads = uploads;
    }

    @Scheduled(cron = "${cheque-extraction.purge-cron:0 0 3 * * *}")
    @Transactional
    public void purge() {
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
        var rows = chequeRepository.findImagePurgeBatch(cutoff, PageRequest.of(0, BATCH_SIZE));
        int ok = 0;
        int failed = 0;

        for (ChequeImagePurgeRow row : rows) {
            try {
                // The blob first: clearing the columns while the file survived would
                // lose the only pointer to it, and the image would sit in storage
                // with nothing left that knows it is there.
                // Only ever a cheque scan (audit C-F2). A row pointing anywhere else
                // (possible before bulk-attach checked its paths) loses the
                // pointer, never the file: that blob belongs to something else.
                if (isChequeImagePath(row.chequeImageBlobPath())) {
                    blob.delete(row.tenantId(), row.chequeImageBlobPath());
                    purgeFamily(row.tenantId(), row.chequeImageBlobPath());
                } else {
                    log.warn("Cheque {} pointed at a non-cheque blob; cleared the reference, kept the blob",
                            row.id());
                }
                chequeRepository.clearImage(row.id());
                ok++;
            } catch (Exception e) {
                failed++;
                log.warn("Failed to purge cheque image {}: {}", row.id(), e.getMessage());
            }
        }

        log.info("Cheque retention purge: cutoff={}, batch={}, deleted={}, failed={}",
                cutoff, rows.size(), ok, failed);
    }

    /**
     * A crop goes with its original (changeset 163). When a cheque's scan was cut
     * from a multi-cheque photo or a PDF, the original — which shows every cheque
     * on it — and any crop of it no cheque ever claimed are deleted too. A crop
     * another cheque holds is left alone: that cheque's own date decides when it
     * goes. Their rows stay (other rows point at them) but are no longer
     * attachable, so nothing can claim a blob that is gone.
     *
     * <p>Explicitly tenant-scoped queries: this job runs with no tenant context,
     * when the Hibernate tenant filter is off.</p>
     */
    private void purgeFamily(UUID tenantId, String purgedPath) {
        var self = uploads.findByTenantIdAndBlobPath(tenantId, purgedPath).orElse(null);
        if (self == null) {
            return;
        }
        UUID rootId = self.getSourceUploadId() != null ? self.getSourceUploadId() : self.getId();
        List<ChequeImageUpload> family = new ArrayList<>(uploads.findByTenantIdAndSourceUploadId(tenantId, rootId));
        if (self.getSourceUploadId() != null) {
            uploads.findByTenantIdAndId(tenantId, rootId).ifPresent(family::add);
        }
        for (ChequeImageUpload u : family) {
            if (u.getId().equals(self.getId()) || u.getChequeId() != null) {
                continue;
            }
            if (isChequeImagePath(u.getBlobPath())) {
                blob.delete(tenantId, u.getBlobPath());
            }
            u.setAttachable(false);
        }
    }

    /** What BlobStorageService.uploadCheque writes: {@code cheques/<uuid><ext>}, one segment, no tricks. */
    static boolean isChequeImagePath(String path) {
        return path != null && path.startsWith("cheques/") && path.indexOf('/', "cheques/".length()) < 0
                && !path.contains("..") && !path.contains("\\");
    }
}
