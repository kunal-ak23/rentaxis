package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PortfolioProfitLossDTO;
import com.datagami.rentaxis.domain.entity.FinancialTransaction;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.repository.AccountMappingRepository;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.FinancialTransactionRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.StaffRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.VendorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinancialTransactionServicePortfolioTest {

    @Mock FinancialTransactionRepository repository;
    @Mock AccountRepository accountRepository;
    @Mock UnitRepository unitRepository;
    @Mock PropertyRepository propertyRepository;
    @Mock VendorRepository vendorRepository;
    @Mock StaffRepository staffRepository;
    @Mock AccountMappingRepository accountMappingRepository;
    @Mock PaymentScheduleRepository paymentScheduleRepository;

    @InjectMocks FinancialTransactionService service;

    @Test
    void returnsOverallFirstAndEveryAuthorizedPropertyIncludingZeroActivity() {
        UUID tenantId = UUID.randomUUID();
        Property alpha = property("Alpha Tower", "برج ألفا");
        Property beta = property("Beta Court", "ساحة بيتا");
        Property zero = property("Zero Activity", "بدون نشاط");
        List<UUID> ids = List.of(alpha.getId(), beta.getId(), zero.getId());

        FinancialTransaction alphaIncome = transaction(alpha, AccountType.INCOME, "C-01-01", 0, 1000);
        FinancialTransaction alphaExpense = transaction(alpha, AccountType.EXPENSE, "D-01-01", 200, 0);
        FinancialTransaction betaIncome = transaction(beta, AccountType.INCOME, "C-01-01", 0, 500);
        when(propertyRepository.findByTenantIdAndIdIn(tenantId, ids))
                .thenReturn(List.of(zero, beta, alpha));
        when(repository.findBySplitParentFalseAndPropertyIdInOrderByDateDesc(ids))
                .thenReturn(List.of(alphaIncome, alphaExpense, betaIncome));

        PortfolioProfitLossDTO result = service.getPortfolioProfitLoss(tenantId, ids, null, null);

        assertThat(result.overall().getTotalIncome()).isEqualByComparingTo("1500");
        assertThat(result.overall().getTotalExpenses()).isEqualByComparingTo("200");
        assertThat(result.overall().getNetProfit()).isEqualByComparingTo("1300");
        assertThat(result.properties()).extracting(PortfolioProfitLossDTO.PropertyProfitLossDTO::propertyNameEn)
                .containsExactly("Alpha Tower", "Beta Court", "Zero Activity");
        assertThat(result.properties().get(0).netProfit()).isEqualByComparingTo("800");
        assertThat(result.properties().get(1).netProfit()).isEqualByComparingTo("500");
        assertThat(result.properties().get(2).netProfit()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(repository).findBySplitParentFalseAndPropertyIdInOrderByDateDesc(ids);
    }

    private static Property property(String nameEn, String nameAr) {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setNameEn(nameEn);
        property.setNameAr(nameAr);
        return property;
    }

    private static FinancialTransaction transaction(
            Property property, AccountType type, String code, int debit, int credit) {
        FinancialTransaction transaction = new FinancialTransaction();
        transaction.setProperty(property);
        transaction.setAccountType(type);
        transaction.setAccountCode(code);
        transaction.setDebit(BigDecimal.valueOf(debit));
        transaction.setCredit(BigDecimal.valueOf(credit));
        return transaction;
    }
}
