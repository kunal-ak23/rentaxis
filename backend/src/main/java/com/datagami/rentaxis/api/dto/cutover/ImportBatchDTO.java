package com.datagami.rentaxis.api.dto.cutover;

import com.datagami.rentaxis.domain.entity.ImportBatch;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchKind;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;

import java.time.Instant;
import java.util.UUID;

/** One cut-over import run as the batches screen shows it (spec §10.3, §11). */
public record ImportBatchDTO(UUID id, ImportBatchKind kind, ImportBatchStatus status, String label,
                             UUID importJobId, UUID repostOf, int leasesImported, int journalsPosted,
                             Instant postedAt, Instant reversedAt, Instant discardedAt, Instant createdAt) {

    public static ImportBatchDTO of(ImportBatch b) {
        return new ImportBatchDTO(b.getId(), b.getKind(), b.getStatus(), b.getLabel(), b.getImportJobId(),
                b.getRepostOf(), b.getLeasesImported(), b.getJournalsPosted(), b.getPostedAt(), b.getReversedAt(),
                b.getDiscardedAt(), b.getCreatedAt());
    }
}
