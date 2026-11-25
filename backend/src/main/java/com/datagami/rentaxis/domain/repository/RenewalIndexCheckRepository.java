package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.RenewalIndexCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Reads run inside a transaction, where the tenant filter scopes them. */
public interface RenewalIndexCheckRepository extends JpaRepository<RenewalIndexCheck, UUID> {

    List<RenewalIndexCheck> findByLeaseIdOrderByDecidedAtAsc(UUID leaseId);
}
