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

    /**
     * #99: how many leases past DRAFT carry a line of this charge type. Such a
     * type's recognition and behaviour are frozen: the books already followed them.
     * JPQL, so the tenant filter applies (callers are {@code @Transactional}).
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(DISTINCT l.lease.id) FROM LeaseLine l
             WHERE l.chargeType.id = :chargeTypeId
               AND l.lease.status <> com.datagami.rentaxis.domain.entity.enums.LeaseStatus.DRAFT""")
    long countNonDraftLeasesUsing(@org.springframework.data.repository.query.Param("chargeTypeId") UUID chargeTypeId);

    /** {@link #findByLease_IdOrderBySeqNoAsc} for a page of leases, credit account fetched. */
    @org.springframework.data.jpa.repository.Query("""
            select l from LeaseLine l left join fetch l.creditAccount
            where l.lease.id in :leaseIds order by l.seqNo asc""")
    List<LeaseLine> findByLeaseIdsOrderBySeqNo(
            @org.springframework.data.repository.query.Param("leaseIds") java.util.Collection<UUID> leaseIds);
}
