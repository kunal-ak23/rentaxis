package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentPenaltyRepository extends JpaRepository<PaymentPenalty, UUID> {

    List<PaymentPenalty> findByLeaseIdOrderByCreatedAtAsc(UUID leaseId);

    Optional<PaymentPenalty> findByPaymentScheduleId(UUID paymentScheduleId);

    Optional<PaymentPenalty> findFirstByPaymentScheduleIdOrderByCreatedAtDesc(UUID paymentScheduleId);

    List<PaymentPenalty> findByLeaseIdAndWaivedFalse(UUID leaseId);
}
