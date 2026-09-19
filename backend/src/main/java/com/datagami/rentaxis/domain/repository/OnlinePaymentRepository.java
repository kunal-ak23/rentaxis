package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.OnlinePayment;
import com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OnlinePaymentRepository extends JpaRepository<OnlinePayment, UUID> {

    Optional<OnlinePayment> findByGatewayOrderId(String orderId);

    /** Every gateway session started against one register row, newest last. */
    List<OnlinePayment> findByCheque_Id(UUID chequeId);

    /** v1 only, and unused since Task 10; dropped with the column in Task 12. */
    List<OnlinePayment> findByPaymentScheduleId(UUID id);

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
}
