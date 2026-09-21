package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.ImportBatchEntity;
import com.datagami.rentaxis.domain.entity.enums.ImportedEntityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * {@code ImportBatchEntity} carries no tenant column, so nothing here is tenant
 * filtered. Every caller must resolve the batch through
 * {@code ImportBatchService.get} first — see the entity for why.
 */
@Repository
public interface ImportBatchEntityRepository extends JpaRepository<ImportBatchEntity, ImportBatchEntity.Key> {

    List<ImportBatchEntity> findByBatchIdOrderByEntityTypeAscEntityIdAsc(UUID batchId);

    /** The other direction: which batch created this row. Used to name it in an error. */
    List<ImportBatchEntity> findByEntityTypeAndEntityId(ImportedEntityType entityType, UUID entityId);
}
