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
}
