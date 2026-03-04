package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.OnlinePayment;
import com.datagami.rentaxis.domain.entity.enums.OnlinePaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OnlinePaymentRepository extends JpaRepository<OnlinePayment, UUID> {

    Optional<OnlinePayment> findByGatewayOrderId(String orderId);

    List<OnlinePayment> findByPaymentScheduleId(UUID id);

    List<OnlinePayment> findByStatus(OnlinePaymentStatus status);

    @Query(value = "SELECT * FROM online_payments WHERE gateway_order_id = ?1", nativeQuery = true)
    Optional<OnlinePayment> findByGatewayOrderIdUnfiltered(String orderId);
}
