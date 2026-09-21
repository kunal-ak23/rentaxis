package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.ImportBatchLease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * {@code ImportBatchLease} carries no tenant column, so nothing here is tenant
 * filtered. Every caller must resolve the batch through
 * {@code ImportBatchService.get} first — see the entity for why.
 */
@Repository
public interface ImportBatchLeaseRepository extends JpaRepository<ImportBatchLease, ImportBatchLease.Key> {

    List<ImportBatchLease> findByBatchIdOrderByLeaseIdAsc(UUID batchId);
}
