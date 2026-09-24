package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.BankStatementProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BankStatementProfileRepository extends JpaRepository<BankStatementProfile, UUID> {
    Optional<BankStatementProfile> findByBankAccountId(UUID bankAccountId);
}
