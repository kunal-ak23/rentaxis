package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.AccountMappingDTO;
import com.datagami.rentaxis.api.dto.SaveAccountMappingDTO;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.AccountMapping;
import com.datagami.rentaxis.domain.entity.enums.TransactionNature;
import com.datagami.rentaxis.domain.repository.AccountMappingRepository;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AccountMappingService {

    private final AccountMappingRepository repository;
    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public List<AccountMappingDTO> getAllMappings() {
        List<AccountMapping> mappings = repository.findAllByOrderByTransactionNatureAsc();
        return mappings.stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Transactional
    public AccountMappingDTO saveMapping(SaveAccountMappingDTO dto) {
        TransactionNature nature = TransactionNature.valueOf(dto.getTransactionNature());
        Account debitAccount = accountRepository.findById(dto.getDebitAccountId())
                .orElseThrow(() -> new RuntimeException("Debit account not found"));
        Account creditAccount = accountRepository.findById(dto.getCreditAccountId())
                .orElseThrow(() -> new RuntimeException("Credit account not found"));

        AccountMapping mapping = repository.findByTransactionNature(nature)
                .orElse(new AccountMapping());

        mapping.setTransactionNature(nature);
        mapping.setDebitAccount(debitAccount);
        mapping.setCreditAccount(creditAccount);

        return toDTO(repository.save(mapping));
    }

    @Transactional
    public List<AccountMappingDTO> saveMappings(List<SaveAccountMappingDTO> dtos) {
        return dtos.stream().map(this::saveMapping).collect(Collectors.toList());
    }

    /**
     * Used by other services (PaymentScheduleService, OnlinePaymentService) to resolve accounts.
     * Falls back to hardcoded defaults if no mapping is configured.
     */
    @Transactional(readOnly = true)
    public AccountMapping resolveMapping(TransactionNature nature) {
        return repository.findByTransactionNature(nature).orElse(null);
    }

    /**
     * Seeds default account mappings for the current tenant.
     * Called after Chart of Accounts are seeded.
     */
    @Transactional
    public void seedDefaults() {
        // Skip if mappings already exist
        List<AccountMapping> existing = repository.findAllByOrderByTransactionNatureAsc();
        if (!existing.isEmpty()) {
            return;
        }

        UUID tenantId = TenantContextHolder.getTenantId();

        // Default mappings: transactionNature -> (debitAccountCode, creditAccountCode)
        // A-02-02 = Bank Accounts, C-01-01 = Rental Income, B-01-02 = Security Deposits
        Map<TransactionNature, String[]> defaults = Map.of(
            TransactionNature.RENT_PAYMENT_CLEARED,       new String[]{"A-02-02", "C-01-01"},
            TransactionNature.SECURITY_DEPOSIT_RECEIVED,   new String[]{"A-02-02", "B-01-02"},
            TransactionNature.SECURITY_DEPOSIT_REFUNDED,   new String[]{"B-01-02", "A-02-02"},
            TransactionNature.CHEQUE_BOUNCED,              new String[]{"C-01-01", "A-02-02"}
        );

        for (var entry : defaults.entrySet()) {
            var debitOpt = accountRepository.findByCodeAndTenantId(entry.getValue()[0], tenantId);
            var creditOpt = accountRepository.findByCodeAndTenantId(entry.getValue()[1], tenantId);

            if (debitOpt.isPresent() && creditOpt.isPresent()) {
                AccountMapping mapping = new AccountMapping();
                mapping.setTransactionNature(entry.getKey());
                mapping.setDebitAccount(debitOpt.get());
                mapping.setCreditAccount(creditOpt.get());
                repository.save(mapping);
            }
        }
    }

    private AccountMappingDTO toDTO(AccountMapping m) {
        AccountMappingDTO dto = new AccountMappingDTO();
        dto.setId(m.getId());
        dto.setTransactionNature(m.getTransactionNature().name());
        dto.setDebitAccountId(m.getDebitAccount().getId());
        dto.setDebitAccountCode(m.getDebitAccount().getCode());
        dto.setDebitAccountName(m.getDebitAccount().getName());
        dto.setCreditAccountId(m.getCreditAccount().getId());
        dto.setCreditAccountCode(m.getCreditAccount().getCode());
        dto.setCreditAccountName(m.getCreditAccount().getName());
        return dto;
    }
}
