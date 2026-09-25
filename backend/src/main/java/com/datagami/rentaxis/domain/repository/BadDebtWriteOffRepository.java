package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.BadDebtWriteOff;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BadDebtWriteOffRepository extends JpaRepository<BadDebtWriteOff, UUID> {

    List<BadDebtWriteOff> findByLeaseIdOrderByProposedAtAsc(UUID leaseId);

    List<BadDebtWriteOff> findByStatusOrderByProposedAtAsc(BadDebtWriteOff.Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from BadDebtWriteOff w where w.id = :id")
    Optional<BadDebtWriteOff> findByIdForUpdate(@Param("id") UUID id);
}
