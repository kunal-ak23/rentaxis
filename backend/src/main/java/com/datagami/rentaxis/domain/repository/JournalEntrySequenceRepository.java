package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.JournalEntrySequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface JournalEntrySequenceRepository extends JpaRepository<JournalEntrySequence, JournalEntrySequence.Key> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from JournalEntrySequence s where s.tenantId = :tenantId and s.docType = :docType and s.fiscalYear = :fiscalYear")
    Optional<JournalEntrySequence> lock(@Param("tenantId") UUID tenantId,
                                        @Param("docType") String docType,
                                        @Param("fiscalYear") int fiscalYear);

    /**
     * Creates the counter row for a tenant's first entry of this doc type and year.
     *
     * <p>Deliberately not {@code save()}: the primary key is assigned, so Spring Data
     * would issue a merge, and a merge that loses the creation race finds the winner's
     * row and <em>updates</em> it — resetting {@code next_value} to 1 and handing the
     * same number out twice. {@code ON CONFLICT DO NOTHING} is a real insert that
     * yields to the winner instead of overwriting it, and unlike a failed insert it
     * does not abort the caller's transaction.
     *
     * <p>Committed in its own transaction so the row is already visible when the
     * caller locks it. Seeding and locking inside one transaction deadlocks: a
     * concurrent caller waiting on the uncommitted row takes the tuple lock the
     * inserter itself then needs for {@code select ... for no key update}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = """
        insert into journal_entry_sequences (tenant_id, doc_type, fiscal_year, next_value)
        values (:tenantId, :docType, :fiscalYear, 1)
        on conflict (tenant_id, doc_type, fiscal_year) do nothing
        """, nativeQuery = true)
    int insertIfAbsent(@Param("tenantId") UUID tenantId,
                       @Param("docType") String docType,
                       @Param("fiscalYear") int fiscalYear);
}
