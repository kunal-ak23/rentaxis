package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LeaseEventRepository extends JpaRepository<LeaseEvent, UUID> {
    List<LeaseEvent> findByLeaseIdOrderByCreatedAtDesc(UUID leaseId);
}
