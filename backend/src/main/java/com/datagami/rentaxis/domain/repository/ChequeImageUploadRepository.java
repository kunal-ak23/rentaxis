package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.ChequeImageUpload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ChequeImageUploadRepository extends JpaRepository<ChequeImageUpload, UUID> {

    Optional<ChequeImageUpload> findByTenantIdAndBlobPath(UUID tenantId, String blobPath);
}
