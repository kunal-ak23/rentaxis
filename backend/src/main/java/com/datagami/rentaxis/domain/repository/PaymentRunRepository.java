package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PaymentRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentRunRepository extends JpaRepository<PaymentRun, UUID> {
    List<PaymentRun> findAllByOrderByCreatedAtAsc();
}
