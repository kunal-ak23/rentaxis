package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.SettlementDeductionAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SettlementDeductionAttachmentRepository extends JpaRepository<SettlementDeductionAttachment, UUID> {
    List<SettlementDeductionAttachment> findByDeductionIdOrderByUploadedAtAsc(UUID deductionId);
    long countByDeductionId(UUID deductionId);
    void deleteByDeductionId(UUID deductionId);
}
