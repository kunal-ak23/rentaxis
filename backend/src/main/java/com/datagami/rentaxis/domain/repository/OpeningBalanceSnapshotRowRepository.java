package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.OpeningBalanceSnapshotRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OpeningBalanceSnapshotRowRepository extends JpaRepository<OpeningBalanceSnapshotRow, UUID> {

    List<OpeningBalanceSnapshotRow> findAllByOrderByAccountCodeAsc();

    Optional<OpeningBalanceSnapshotRow> findByAccountCode(String accountCode);

    /**
     * Clears the tenant's snapshot before a re-upload. A bulk delete rather than
     * {@code deleteAll(findAll())}: the previous upload can be several hundred rows,
     * and loading every one of them into the persistence context to delete it makes
     * the re-upload quadratic for no benefit.
     *
     * <p><b>On the explicit {@code tenantId} predicate.</b> It is defence in depth,
     * and this is the measurement rather than the assumption: Hibernate 7 <em>does</em>
     * apply an enabled {@code @Filter} to a bulk delete (the SQM engine translates it
     * into the statement's restriction, which Hibernate 5 did not), so replacing this
     * predicate with a tautology still leaves another organisation's snapshot intact —
     * {@code OpeningBalanceIT#tenantBCannotSeeOrClearTenantAsSnapshotOrOpenItsBooks}
     * keeps passing. Keep it anyway: it is the scope that survives the filter being
     * off, which is the state every caller outside a {@code domain.repository..*} call
     * is in, and it costs nothing.</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from OpeningBalanceSnapshotRow r where r.tenantId = :tenantId")
    int deleteAllForTenant(@Param("tenantId") UUID tenantId);
}
