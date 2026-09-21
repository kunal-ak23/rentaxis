package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Voucher;
import com.datagami.rentaxis.domain.entity.enums.VoucherStatus;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface VoucherRepository extends JpaRepository<Voucher, UUID> {

    @Query("""
        select v from Voucher v
        where (:docType is null or v.docType = :docType)
          and (:status is null or v.status = :status)
          and (:vendorId is null or v.vendor.id = :vendorId)
          and (:propertyId is null or v.propertyId = :propertyId)
          and (:from is null or v.docDate >= :from)
          and (:to is null or v.docDate <= :to)
        order by v.docDate desc, v.createdAt desc
        """)
    Page<Voucher> search(VoucherType docType, VoucherStatus status, UUID vendorId, UUID propertyId,
                         LocalDate from, LocalDate to, Pageable pageable);

    List<Voucher> findByJournalId(UUID journalId);

    boolean existsByPaymentAccount_Id(UUID accountId);
}
