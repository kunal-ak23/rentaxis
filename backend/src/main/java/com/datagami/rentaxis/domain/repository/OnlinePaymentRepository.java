package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.OnlinePayment;
import com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
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
     * When the newest still-open checkout on this row was started, or null when
     * there is none.
     *
     * <p>An {@code ONLINE_PENDING} row is owed money ({@code ChequeDueRules.due})
     * but it is not always worth chasing: a renter who is on Razorpay's page right
     * now should not get an overdue reminder about the instalment they are in the
     * middle of paying. {@code NotificationScheduler} asks this and keeps quiet
     * while the answer is inside its window; past it the row is an abandonment and
     * is chased like any other.</p>
     *
     * <p>Unfiltered by tenant in practice, and deliberately: the caller is a
     * scheduled sweep with no tenant context, and it asks about one cheque id it
     * already holds.</p>
     */
    @Query("""
        select max(o.createdAt) from OnlinePayment o
        where o.cheque.id = :chequeId
          and o.status = com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus.CREATED
        """)
    Instant latestOpenCheckoutStartedAt(@Param("chequeId") UUID chequeId);

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

    /**
     * Fail one still-open checkout — <em>only</em> if it is still open (issue #286).
     *
     * <p>The rewrite that supersedes an abandoned session decides from rows that
     * were read a moment earlier, and a gateway capture can commit inside that
     * window: the entity in memory still says CREATED, so saving it wrote FAILED
     * over a row the webhook had just marked CAPTURED — the one transition the
     * MONEY_CAPTURED rule says never happens. A conditional UPDATE cannot do that
     * whatever the reader believed: the status test is evaluated by the database,
     * against the row as it is now, under the row lock the UPDATE itself takes.</p>
     *
     * <p>Callers must treat {@code 0} as "the row moved" and go and look, not as
     * "nothing to do" — see {@code OnlinePaymentService#failOpenCheckouts}.</p>
     *
     * @return 1 when this call failed the session, 0 when it was no longer CREATED.
     */
    @Modifying
    @Query("""
            update OnlinePayment o
               set o.status = com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus.FAILED,
                   o.failureReason = :reason,
                   o.updatedAt = :now
             where o.id = :id
               and o.status = com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus.CREATED
            """)
    int failIfStillOpen(@Param("id") UUID id, @Param("reason") String reason, @Param("now") Instant now);

    /**
     * This payment's status as the database holds it right now.
     *
     * <p>A scalar projection rather than {@code findById}: the entity is already in
     * the persistence context with the state it was read with, and asking for it
     * again would hand back that same stale instance. This goes to the row.</p>
     */
    @Query("select o.status from OnlinePayment o where o.id = :id")
    Optional<OnlinePaymentStatus> currentStatus(@Param("id") UUID id);

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
