package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.AccountMapping;
import com.datagami.rentaxis.domain.entity.enums.TransactionNature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountMappingRepository extends JpaRepository<AccountMapping, UUID> {
    Optional<AccountMapping> findByTransactionNature(TransactionNature transactionNature);
    List<AccountMapping> findAllByOrderByTransactionNatureAsc();
}
