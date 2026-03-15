package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface LeaseAttachmentRepository extends JpaRepository<LeaseAttachment, UUID> {
    List<LeaseAttachment> findByLeaseId(UUID leaseId);
}
