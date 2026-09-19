package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.OnlinePayment;
import com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OnlinePaymentRepository extends JpaRepository<OnlinePayment, UUID> {

    Optional<OnlinePayment> findByGatewayOrderId(String orderId);

    /** Every gateway session started against one register row, newest last. */
    List<OnlinePayment> findByCheque_Id(UUID chequeId);

    List<OnlinePayment> findByStatus(OnlinePaymentStatus status);

    @Query(value = "SELECT * FROM online_payments WHERE gateway_order_id = ?1", nativeQuery = true)
    Optional<OnlinePayment> findByGatewayOrderIdUnfiltered(String orderId);

    /**
     * The payment row, locked, so the two reports of one capture cannot both post.
     *
     * <p>A gateway capture is announced twice — once by the browser redirecting
     * into {@code verifyPayment}, once by the webhook — in either order and
     * sometimes at the same moment. {@code ChequeService.clearOnline} is idempotent
     * and takes the cheque's own NOWAIT lock, but "clear the cheque and mark this
     * payment captured" has to be one atomic step or the second caller can observe
     * a payment that is still CREATED and try to clear a row the first caller is
     * halfway through.</p>
     *
     * <p><b>Blocking, unlike the register's NOWAIT locks.</b> The loser here should
     * wait a few milliseconds and then find the work already done — an idempotent
     * no-op — rather than fail and rely on the gateway retrying. The critical
     * section is one cheque transition long, and serialising on this row is what
     * leaves the cheque lock uncontended.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OnlinePayment o where o.id = :id")
    Optional<OnlinePayment> findByIdForUpdate(@Param("id") UUID id);

    /** {@link #findByIdForUpdate} for the client callback, which knows only the order id. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OnlinePayment o where o.gatewayOrderId = :orderId")
    Optional<OnlinePayment> findByGatewayOrderIdForUpdate(@Param("orderId") String orderId);

    /**
     * Finance's refund worklist: captures the gateway took that the register
     * refused ({@code CAPTURED_UNAPPLIED}), newest capture first.
     *
     * <p><b>Tenant-scoped twice.</b> The explicit {@code tenantId} predicate is
     * the query's own guarantee; the Hibernate tenant filter (on inside the
     * caller's transaction) is the second. A null tenant matches nothing.</p>
     *
     * <p>Everything the row renders is fetch-joined (cheque, its lease, property,
     * unit and renter — all to-one, so the page limit still runs in SQL): without
     * it a page of 25 is five lazy loads per row. The count query carries no
     * fetch joins, which Hibernate rejects in a count.</p>
     *
     * <p>The order is fixed here, not taken from the {@code Pageable}: pass an
     * unsorted one. {@code updatedAt} is when the capture was recorded as
     * unappliable.</p>
     */
    @Query(value = """
            select o from OnlinePayment o
            join fetch o.cheque c
            join fetch c.lease l
            join fetch c.property p
            left join fetch c.unit u
            join fetch c.renter r
            where o.tenantId = :tenantId
              and o.status = com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus.CAPTURED_UNAPPLIED
            order by o.updatedAt desc nulls last, o.createdAt desc, o.id
            """,
            countQuery = """
            select count(o) from OnlinePayment o
            where o.tenantId = :tenantId
              and o.status = com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus.CAPTURED_UNAPPLIED
            """)
    Page<OnlinePayment> findUnapplied(@Param("tenantId") UUID tenantId, Pageable pageable);

    /** How many refunds are outstanding — the dashboard tile's count. */
    @Query("""
            select count(o) from OnlinePayment o
            where o.tenantId = :tenantId
              and o.status = com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus.CAPTURED_UNAPPLIED
            """)
    long countUnapplied(@Param("tenantId") UUID tenantId);

    /** What they add up to. Zero, never null. */
    @Query("""
            select coalesce(sum(o.amount), 0) from OnlinePayment o
            where o.tenantId = :tenantId
              and o.status = com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus.CAPTURED_UNAPPLIED
            """)
    BigDecimal sumUnapplied(@Param("tenantId") UUID tenantId);
}
