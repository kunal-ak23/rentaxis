package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LeaseLineRepository extends JpaRepository<LeaseLine, UUID> {

    /** The lease's lines in entry order — the order the contract renders them in. */
    List<LeaseLine> findByLease_IdOrderBySeqNoAsc(UUID leaseId);

    /**
     * Drop the lease's lines. Delete-then-insert is how a draft's lines are
     * replaced, so this runs on every edit.
     *
     * <p>A derived delete, so Spring Data loads the rows and removes them one by
     * one rather than issuing a bulk statement — which is what keeps the tenant
     * filter and the entity lifecycle applying to them, exactly as they would to
     * a read.</p>
     */
    @Modifying
    void deleteByLease_Id(UUID leaseId);
}
