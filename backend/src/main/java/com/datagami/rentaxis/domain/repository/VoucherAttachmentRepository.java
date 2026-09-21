package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.VoucherAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VoucherAttachmentRepository extends JpaRepository<VoucherAttachment, UUID> {
    List<VoucherAttachment> findByVoucherIdOrderByUploadedAtAsc(UUID voucherId);
    long countByVoucherId(UUID voucherId);
}
