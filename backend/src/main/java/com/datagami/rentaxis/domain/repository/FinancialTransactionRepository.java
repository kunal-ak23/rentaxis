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

    List<FinancialTransaction> findAllByOrderByDateDesc();

    List<FinancialTransaction> findByDateBetweenOrderByDateDesc(LocalDate startDate, LocalDate endDate);

    List<FinancialTransaction> findByPropertyIdAndDateBetween(UUID propertyId, LocalDate startDate, LocalDate endDate);

    List<FinancialTransaction> findByUnitIdAndDateBetween(UUID unitId, LocalDate startDate, LocalDate endDate);

    /**
     * For property-level reports: all transactions tagged to this property OR any
     * of its units.
     */
    List<FinancialTransaction> findByPropertyIdOrUnitPropertyId(UUID propertyId, UUID unitPropertyId);

    List<FinancialTransaction> findByVendorId(UUID vendorId);

    List<FinancialTransaction> findByVendorIdAndDateBetween(UUID vendorId, LocalDate start, LocalDate end);

    List<FinancialTransaction> findByStaffId(UUID staffId);

    // Split transaction queries: exclude child transactions (parent_transaction_id IS NULL)
    List<FinancialTransaction> findByParentTransactionIsNullOrderByDateDesc();

    List<FinancialTransaction> findByParentTransactionIsNullAndPropertyIdOrderByDateDesc(UUID propertyId);

    List<FinancialTransaction> findByParentTransactionIsNullAndAccountTypeOrderByDateDesc(AccountType accountType);

    List<FinancialTransaction> findByParentTransactionIsNullAndDateBetweenOrderByDateDesc(LocalDate startDate, LocalDate endDate);

    List<FinancialTransaction> findByParentTransactionIsNullAndPropertyIdAndDateBetweenOrderByDateDesc(UUID propertyId, LocalDate startDate, LocalDate endDate);

    // Fetch children for a specific parent
    List<FinancialTransaction> findByParentTransaction_Id(UUID parentTransactionId);

    // Aggregate report queries: exclude split parents (is_split_parent = true) to avoid double-counting
    List<FinancialTransaction> findBySplitParentFalseOrderByDateDesc();

    List<FinancialTransaction> findBySplitParentFalseAndDateBetweenOrderByDateDesc(LocalDate startDate, LocalDate endDate);
}
