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
     * Money the gateway took that the register refused — finance's refund worklist.
     *
     * <p>Every relation the screen renders is fetch-joined. Without them this is
     * five lazy loads per row (cheque → lease → unit → property, and renter), which
     * on a page of twenty is a hundred queries for a list nobody paginates past.
     * {@code countQuery} is given separately because a fetch join has no place in a
     * count and Hibernate rejects one.</p>
     *
     * <p>Tenant scoping is the Hibernate filter's, as everywhere else: this runs
     * inside a transaction, so {@code TenantAspect} has the filter on and one
     * landlord's unapplied captures are invisible to another.</p>
     */
    @Query(value = """
            select o from OnlinePayment o
            join fetch o.cheque c
            join fetch c.lease l
            left join fetch l.unit u
            left join fetch u.property p
            left join fetch c.renter r
            where o.status = com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus.CAPTURED_UNAPPLIED
            """,
            countQuery = """
            select count(o) from OnlinePayment o
            where o.status = com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus.CAPTURED_UNAPPLIED
            """)
    Page<OnlinePayment> findUnapplied(Pageable pageable);

    /**
     * The dashboard tile, as two scalars rather than one tuple: a multi-select JPQL
     * aggregate comes back as {@code Object[]} and has to be unpacked by position,
     * which is a cast nobody checks until it breaks at runtime.
     */
    @Query("""
            select count(o) from OnlinePayment o
            where o.status = com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus.CAPTURED_UNAPPLIED
            """)
    long countUnapplied();

    /** How much is owed back, in the tenant's own currency. Zero, never null. */
    @Query("""
            select coalesce(sum(o.amount), 0) from OnlinePayment o
            where o.status = com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus.CAPTURED_UNAPPLIED
            """)
    BigDecimal sumUnapplied();
}
