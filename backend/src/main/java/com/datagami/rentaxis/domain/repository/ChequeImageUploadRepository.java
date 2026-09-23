package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.ChequeImageUpload;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ChequeImageUploadRepository extends JpaRepository<ChequeImageUpload, UUID> {

    Optional<ChequeImageUpload> findByTenantIdAndBlobPath(UUID tenantId, String blobPath);

    /**
     * The issued scan, locked until the transaction ends. Bulk-attach reads
     * "unclaimed" and then claims it; without the lock two concurrent attaches
     * both read {@code cheque_id IS NULL} and both cheques end up carrying the
     * same image — and the retention purge deletes it from under the survivor.
     * Waits (no NOWAIT): the second caller then re-reads a claimed row and is
     * refused with the ordinary "image not issued".
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from ChequeImageUpload u where u.tenantId = :tenantId and u.blobPath = :blobPath")
    Optional<ChequeImageUpload> findByTenantIdAndBlobPathForUpdate(@Param("tenantId") UUID tenantId,
                                                                    @Param("blobPath") String blobPath);

    /**
     * Releases every scan a cheque holds except {@code keepId}, so the cheque can
     * claim a new one ({@code cheque_id} is unique, changeset 105).
     */
    @Modifying(flushAutomatically = true)
    @Query("update ChequeImageUpload u set u.chequeId = null where u.chequeId = :chequeId and u.id <> :keepId")
    int releaseOtherClaimsOf(@Param("chequeId") UUID chequeId, @Param("keepId") UUID keepId);

    /** Releases every scan a cheque holds: its image was removed. */
    @Modifying(flushAutomatically = true)
    @Query("update ChequeImageUpload u set u.chequeId = null where u.chequeId = :chequeId")
    int releaseClaimsOf(@Param("chequeId") UUID chequeId);
}
