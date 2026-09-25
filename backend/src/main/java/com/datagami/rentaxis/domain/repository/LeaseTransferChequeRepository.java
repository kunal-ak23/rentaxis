package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseTransferCheque;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LeaseTransferChequeRepository extends JpaRepository<LeaseTransferCheque, UUID> {

    List<LeaseTransferCheque> findBySuccessorLeaseId(UUID successorLeaseId);

    @Modifying
    void deleteBySuccessorLeaseId(UUID successorLeaseId);
}
