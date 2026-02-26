package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.FinancialTransaction;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface FinancialTransactionRepository extends JpaRepository<FinancialTransaction, UUID> {

    List<FinancialTransaction> findByPropertyId(UUID propertyId);

    List<FinancialTransaction> findByUnitId(UUID unitId);

    List<FinancialTransaction> findByAccountType(AccountType accountType);

    List<FinancialTransaction> findByDateBetween(LocalDate startDate, LocalDate endDate);

    List<FinancialTransaction> findByPropertyIdAndDateBetween(UUID propertyId, LocalDate startDate, LocalDate endDate);

    List<FinancialTransaction> findByUnitIdAndDateBetween(UUID unitId, LocalDate startDate, LocalDate endDate);

    /**
     * For property-level reports: all transactions tagged to this property OR any
     * of its units.
     */
    List<FinancialTransaction> findByPropertyIdOrUnitPropertyId(UUID propertyId, UUID unitPropertyId);
}
