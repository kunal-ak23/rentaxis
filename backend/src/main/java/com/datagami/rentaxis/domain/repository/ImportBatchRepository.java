package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.ImportBatch;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {

    /** Oldest first — the house convention for entity lists (createdAt, never updatedAt). */
    List<ImportBatch> findAllByOrderByCreatedAtAsc();

    /**
     * The successor batches created to re-post one reversed batch (spec §10.3, R12).
     *
     * <p>A list rather than an {@code Optional} because nothing in the database makes
     * it unique: a reversed batch that was re-posted, and whose successor then posted
     * and was reversed in turn, can legitimately acquire a second one.
     * {@code ImportBatchService.successorOf} narrows to the DRAFT one, which is the
     * at-most-one an unfinished run can have left behind.</p>
     */
    List<ImportBatch> findByRepostOfAndStatus(UUID repostOf, ImportBatchStatus status);

    /**
     * Does this organisation have a batch in that state at all?
     *
     * <p>{@code TenantFiscalSettingsService} asks it about POSTED, to freeze the
     * books start date while a cut-over is on the books (review I4, ruling R21).
     * Tenant-scoped by the Hibernate filter, like every other finder here.</p>
     */
    boolean existsByStatus(ImportBatchStatus status);
}
