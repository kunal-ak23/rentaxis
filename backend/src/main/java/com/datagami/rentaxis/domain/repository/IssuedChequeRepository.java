package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.IssuedCheque;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IssuedChequeRepository extends JpaRepository<IssuedCheque, UUID> {
    List<IssuedCheque> findAllByOrderByChequeDateAscCreatedAtAsc();
    Optional<IssuedCheque> findFirstByVoucherIdAndStatusNot(UUID voucherId, IssuedCheque.Status status);
    List<IssuedCheque> findByVoucherId(UUID voucherId);

    /** Cheques not CANCELLED with this number on this bank leaf, other than {@code excludeVoucherId}'s own. */
    @org.springframework.data.jpa.repository.Query("""
        select count(c) from IssuedCheque c
        where c.bankAccountId = :bank and c.chequeNumber = :no
          and c.status <> com.datagami.rentaxis.domain.entity.IssuedCheque.Status.CANCELLED
          and (:exclude is null or c.voucherId is null or c.voucherId <> :exclude)
        """)
    long countOutstandingNumber(@org.springframework.data.repository.query.Param("bank") UUID bank,
                                @org.springframework.data.repository.query.Param("no") String no,
                                @org.springframework.data.repository.query.Param("exclude") UUID exclude);

    /**
     * Scale P1-3: the issued-cheque register, filtered and paged in the database (the list
     * endpoint filters the whole register in memory). {@code duePresent}: ISSUED and dated on
     * or before {@code today}.
     */
    @org.springframework.data.jpa.repository.Query("""
        select c from IssuedCheque c
        where c.tenantId = :tenantId
          and (cast(:status as string) is null or c.status = :status)
          and (cast(:bankAccountId as java.util.UUID) is null or c.bankAccountId = :bankAccountId)
          and (cast(:from as LocalDate) is null or c.chequeDate >= :from)
          and (cast(:to as LocalDate) is null or c.chequeDate <= :to)
          and (:duePresent = false
               or (c.status = :issuedStatus and c.chequeDate <= :today))
        """)
    org.springframework.data.domain.Page<IssuedCheque> searchPaged(
            @org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
            @org.springframework.data.repository.query.Param("status") IssuedCheque.Status status,
            @org.springframework.data.repository.query.Param("bankAccountId") UUID bankAccountId,
            @org.springframework.data.repository.query.Param("from") java.time.LocalDate from,
            @org.springframework.data.repository.query.Param("to") java.time.LocalDate to,
            @org.springframework.data.repository.query.Param("duePresent") boolean duePresent,
            @org.springframework.data.repository.query.Param("today") java.time.LocalDate today,
            @org.springframework.data.repository.query.Param("issuedStatus") IssuedCheque.Status issuedStatus,
            org.springframework.data.domain.Pageable pageable);
}
