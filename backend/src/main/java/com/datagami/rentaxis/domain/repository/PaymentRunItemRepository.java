package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PaymentRunItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentRunItemRepository extends JpaRepository<PaymentRunItem, UUID> {
    List<PaymentRunItem> findByRunId(UUID runId);
    List<PaymentRunItem> findByRunIdIn(Collection<UUID> runIds);
    void deleteByRunId(UUID runId);
}
