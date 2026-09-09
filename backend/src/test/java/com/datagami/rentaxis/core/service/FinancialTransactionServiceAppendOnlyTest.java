package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.FinancialTransaction;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.repository.AccountMappingRepository;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.FinancialTransactionRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.StaffRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.VendorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ledger is append-only: FinancialTransactionController exposes no PUT and
 * no DELETE. But POST bound the entity directly and {@code repository.save()}
 * with a non-null id is a merge, so a request echoing back a fetched
 * transaction rewrote that row — including legs auto-posted when a cheque
 * cleared or bounced — with nothing to distinguish the result from an original
 * posting.
 */
class FinancialTransactionServiceAppendOnlyTest {

    private FinancialTransactionRepository repository;
    private AccountRepository accountRepository;
    private FinancialTransactionService service;

    @BeforeEach
    void setUp() {
        repository = mock(FinancialTransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        service = new FinancialTransactionService(
                repository,
                accountRepository,
                mock(UnitRepository.class),
                mock(PropertyRepository.class),
                mock(VendorRepository.class),
                mock(StaffRepository.class),
                mock(AccountMappingRepository.class),
                mock(PaymentScheduleRepository.class));
        when(repository.save(any(FinancialTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private FinancialTransaction txn() {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setCode("C-01-01");
        account.setAccountType(AccountType.INCOME);

        // createTransaction re-resolves the account to avoid detached-entity
        // errors, so the repository has to hand the same one back.
        when(accountRepository.findById(account.getId())).thenReturn(java.util.Optional.of(account));

        FinancialTransaction t = new FinancialTransaction();
        t.setDate(LocalDate.now());
        t.setDescription("Rent - installment 3");
        t.setAccount(account);
        t.setDebit(BigDecimal.ZERO);
        t.setCredit(new BigDecimal("5000"));
        return t;
    }

    @Test
    void postingWithAnExistingIdIsRejectedRatherThanOverwritingTheRow() {
        FinancialTransaction echoed = txn();
        echoed.setId(UUID.randomUUID()); // the id of an already-posted row

        assertThatThrownBy(() -> service.createTransaction(echoed))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("reversing entry");

        // The critical assertion: nothing reached the repository, so the
        // existing row was not merged over.
        verify(repository, never()).save(any(FinancialTransaction.class));
    }

    @Test
    void anOrdinaryPostingWithNoIdStillSucceeds() {
        assertThatCode(() -> service.createTransaction(txn())).doesNotThrowAnyException();
        verify(repository).save(any(FinancialTransaction.class));
    }

    @Test
    void postingStampsCreatedAndUpdatedTimestamps() {
        FinancialTransaction saved = service.createTransaction(txn());

        // The columns existed in 06-chart-of-accounts but were unmapped, so
        // Hibernate never wrote them and an edit left no trace at all.
        saved.onPrePersistFinancial();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void createdAtIsPreservedAcrossUpdatesWhileUpdatedAtMoves() throws Exception {
        FinancialTransaction t = txn();
        t.onPrePersistFinancial();
        java.time.Instant firstCreated = t.getCreatedAt();
        java.time.Instant firstUpdated = t.getUpdatedAt();

        Thread.sleep(5);
        t.onPrePersistFinancial();

        assertThat(t.getCreatedAt()).isEqualTo(firstCreated);
        assertThat(t.getUpdatedAt()).isAfterOrEqualTo(firstUpdated);
    }
}
