package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BankAccountRepository extends JpaRepository<BankAccount, UUID> {
    List<BankAccount> findByPropertyId(UUID propertyId);
    List<BankAccount> findByIsActiveTrue();
    Optional<BankAccount> findByIsDefaultTrue();
    List<BankAccount> findAllByOrderByBankNameAsc();
}
