package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.TenantGatewayConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantGatewayConfigRepository extends JpaRepository<TenantGatewayConfig, UUID> {

    Optional<TenantGatewayConfig> findByGatewayIdAndIsActiveTrue(UUID gatewayId);

    List<TenantGatewayConfig> findByIsActiveTrue();
}
