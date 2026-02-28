package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LeaseDocumentRepository extends JpaRepository<LeaseDocument, UUID> {
    List<LeaseDocument> findByLeaseId(UUID leaseId);
}
