package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PaymentGateway;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentGatewayRepository extends JpaRepository<PaymentGateway, UUID> {

    Optional<PaymentGateway> findByCode(String code);

    List<PaymentGateway> findByIsActiveTrue();
}
