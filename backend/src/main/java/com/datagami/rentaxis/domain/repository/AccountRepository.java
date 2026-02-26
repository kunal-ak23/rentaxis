package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByCode(String code);

    List<Account> findByAccountType(AccountType accountType);

    List<Account> findByParentCode(String parentCode);
}
