package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.VoucherAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Read side only for the service; {@code VoucherAllocationService} is the one writer. */
@Repository
public interface VoucherAllocationRepository extends JpaRepository<VoucherAllocation, UUID> {

    @Query("""
        select a from VoucherAllocation a
        where a.paymentVoucherId = :voucherId or a.invoiceVoucherId = :voucherId
        order by a.allocatedOn, a.createdAt
        """)
    List<VoucherAllocation> findTouching(@Param("voucherId") UUID voucherId);

    List<VoucherAllocation> findByOpeningItemIdOrderByAllocatedOnAscCreatedAtAsc(UUID openingItemId);

    List<VoucherAllocation> findByPaymentVoucherIdAndReleasedOnIsNull(UUID paymentVoucherId);

    List<VoucherAllocation> findByInvoiceVoucherIdAndReleasedOnIsNull(UUID invoiceVoucherId);

    @Query("select coalesce(sum(a.amount), 0) from VoucherAllocation a where a.paymentVoucherId = :id and a.releasedOn is null")
    BigDecimal liveTotalForPayment(@Param("id") UUID paymentVoucherId);

    @Query("select coalesce(sum(a.amount), 0) from VoucherAllocation a where a.invoiceVoucherId = :id and a.releasedOn is null")
    BigDecimal liveTotalForInvoice(@Param("id") UUID invoiceVoucherId);

    @Query("select coalesce(sum(a.amount), 0) from VoucherAllocation a where a.openingItemId = :id and a.releasedOn is null")
    BigDecimal liveTotalForOpeningItem(@Param("id") UUID openingItemId);

    boolean existsByOpeningItemId(UUID openingItemId);
}
