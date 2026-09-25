package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.BadDebtRecovery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BadDebtRecoveryRepository extends JpaRepository<BadDebtRecovery, UUID> {

    List<BadDebtRecovery> findByWriteOffIdOrderByRecoveredOnAsc(UUID writeOffId);
}
