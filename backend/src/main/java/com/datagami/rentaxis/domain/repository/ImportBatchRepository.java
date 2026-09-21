package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {

    /** Oldest first — the house convention for entity lists (createdAt, never updatedAt). */
    List<ImportBatch> findAllByOrderByCreatedAtAsc();
}
