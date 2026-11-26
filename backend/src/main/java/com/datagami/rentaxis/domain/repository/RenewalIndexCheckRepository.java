package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.RenewalIndexCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Reads run inside a transaction, where the tenant filter scopes them. */
public interface RenewalIndexCheckRepository extends JpaRepository<RenewalIndexCheck, UUID> {

    List<RenewalIndexCheck> findByLeaseIdOrderByDecidedAtAsc(UUID leaseId);

    /** The checks written for one successor draft, newest first (the draft-edit check compares with the first). */
    List<RenewalIndexCheck> findBySuccessorLeaseIdOrderByDecidedAtDesc(UUID successorLeaseId);

    /** Every check a lease took part in, as the lease renewed or as the successor drafted, newest first. */
    List<RenewalIndexCheck> findByLeaseIdOrSuccessorLeaseIdOrderByDecidedAtDesc(UUID leaseId, UUID successorLeaseId);
}
