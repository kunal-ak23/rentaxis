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

    /**
     * The tenant's active gateway configurations, oldest first.
     *
     * <p>Every caller of the unordered {@code findByIsActiveTrue} took
     * {@code get(0)} of a list the database was free to return in any order, and
     * they all meant "the" active config: the one whose <em>secret</em> a webhook
     * is verified against, whose credentials an order is created with, and whose
     * settlement account a capture debits. With two active rows — a stale one and
     * the one an admin just saved — those three could silently disagree between
     * requests, and a signature checked against the wrong secret simply fails.
     * Ordering makes "the first active config" mean the same row every time.</p>
     *
     * <p>{@code createdAt} is nullable on rows written before it was populated;
     * Postgres sorts nulls last on ASC, and the id breaks any remaining tie.</p>
     */
    List<TenantGatewayConfig> findByIsActiveTrueOrderByCreatedAtAscIdAsc();
}
