package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.BankStatementImport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BankStatementImportRepository extends JpaRepository<BankStatementImport, UUID> {
    List<BankStatementImport> findByBankAccountIdOrderByImportedAtDesc(UUID bankAccountId);
}
