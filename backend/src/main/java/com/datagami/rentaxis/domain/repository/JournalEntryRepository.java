package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    List<JournalEntry> findBySourceTypeAndSourceIdOrderByEntryDateAscCreatedAtAsc(JournalSourceType sourceType, UUID sourceId);

    /**
     * Every filter is optional. The casts are not cosmetic: without them Postgres
     * sees a bare {@code $n is null} with nothing to infer a type from and rejects
     * the statement with "could not determine data type of parameter" as soon as a
     * caller passes a non-null filter.
     */
    @Query("""
        select e from JournalEntry e
        where (cast(:docType as string) is null or e.docType = :docType)
          and (cast(:from as LocalDate) is null or e.entryDate >= :from)
          and (cast(:to as LocalDate) is null or e.entryDate <= :to)
          and (cast(:propertyId as java.util.UUID) is null or e.propertyId = :propertyId)
          and (cast(:leaseId as java.util.UUID) is null or e.leaseId = :leaseId)
        order by e.entryDate desc, e.createdAt desc
        """)
    Page<JournalEntry> search(@Param("docType") JournalDocType docType,
                              @Param("from") LocalDate from,
                              @Param("to") LocalDate to,
                              @Param("propertyId") UUID propertyId,
                              @Param("leaseId") UUID leaseId,
                              Pageable pageable);

    List<JournalEntry> findByImportBatchIdOrderByCreatedAtAsc(UUID importBatchId);
}
