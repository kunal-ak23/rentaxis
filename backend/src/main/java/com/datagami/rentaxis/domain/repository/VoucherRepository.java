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

    /**
     * {@code from}/{@code to} are never null by the time they reach here — see
     * {@code VoucherService.list}, which substitutes sentinel dates. Binding an
     * actual {@code LocalDate} into {@code (:from is null or v.docDate >= :from)}
     * makes Postgres refuse the query with "could not determine data type of
     * parameter": unlike the UUID/enum filters above, whose type is unambiguous
     * from their own {@code =} comparison, the driver cannot infer a type for a
     * temporal parameter from the bare {@code :from is null} occurrence alone.
     * {@code doc_date} is {@code NOT NULL} (changeset 87), so a plain {@code >=}/
     * {@code <=} against a sentinel is exactly equivalent to "no bound".
     */
    @Query("""
        select v from Voucher v
        where (:docType is null or v.docType = :docType)
          and (:status is null or v.status = :status)
          and (:vendorId is null or v.vendor.id = :vendorId)
          and (:propertyId is null or v.propertyId = :propertyId)
          and v.docDate >= :from
          and v.docDate <= :to
        order by v.createdAt asc
        """)
    Page<Voucher> search(VoucherType docType, VoucherStatus status, UUID vendorId, UUID propertyId,
                         LocalDate from, LocalDate to, Pageable pageable);
}
