package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.PropertyAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.TenantDefaultAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Chart-of-accounts guard rails must map to client-error statuses, not 500s:
 * GlobalExceptionHandler turns NotFoundException into 404 and
 * BusinessRuleViolationException into 400, while a bare RuntimeException
 * (the previous behavior) fell into the catch-all 500 handler.
 */
class AccountServiceTest {

    private AccountRepository repository;
    private JournalLineRepository journalLineRepository;
    private PropertyAccountMappingRepository propertyAccountMappingRepository;
    private TenantDefaultAccountMappingRepository tenantDefaultAccountMappingRepository;
    private AccountService service;

    @BeforeEach
    void setUp() {
        repository = mock(AccountRepository.class);
        journalLineRepository = mock(JournalLineRepository.class);
        propertyAccountMappingRepository = mock(PropertyAccountMappingRepository.class);
        tenantDefaultAccountMappingRepository = mock(TenantDefaultAccountMappingRepository.class);
        service = new AccountService(repository, mock(TenantFiscalSettingsRepository.class),
                mock(PropertyRepository.class), journalLineRepository, propertyAccountMappingRepository,
                tenantDefaultAccountMappingRepository);
    }

    private Account account(boolean system) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setCode("D-99");
        a.setName("Landscaping");
        a.setSystem(system);
        return a;
    }

    @Test
    void updateAccount_unknownId_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAccount(id, update(null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateAccount_systemAccount_throwsBusinessRuleViolation() {
        Account system = account(true);
        when(repository.findById(system.getId())).thenReturn(Optional.of(system));

        assertThatThrownBy(() -> service.updateAccount(system.getId(), update(null, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("System accounts cannot be modified");
        verify(repository, never()).save(any());
    }

    /** An update carrying only the two fields under test; everything else is null. */
    private AccountService.AccountUpdate update(Boolean active, Integer displayOrder) {
        return new AccountService.AccountUpdate("Renamed", null, null, "LAND", null, null,
                active, displayOrder, null);
    }

    @Test
    void updateAccount_ignoresCodeTypeParentAndGroupChanges() {
        Account existing = account(false);
        Account currentParent = account(false);
        currentParent.setCode("D-01");
        existing.setParent(currentParent);
        existing.setGroup(false);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account saved = service.updateAccount(existing.getId(), update(false, 9));

        assertThat(saved.getCode()).isEqualTo("D-99");
        assertThat(saved.getParentId()).isEqualTo(currentParent.getId());
        assertThat(saved.isGroup()).isFalse();
        assertThat(saved.getName()).isEqualTo("Renamed");
        assertThat(saved.getAlias()).isEqualTo("LAND");
        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getDisplayOrder()).isEqualTo(9);
    }

    /**
     * A body that names neither field must leave both alone. With primitives on
     * the request record this call deactivated the account and reset its
     * ordering to 0, because that is what {@code boolean}/{@code int} deserialise
     * to when the JSON omits them.
     */
    @Test
    void updateAccount_nullActiveAndDisplayOrder_leaveThemUnchanged() {
        Account existing = account(false);
        existing.setActive(true);
        existing.setDisplayOrder(7);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account saved = service.updateAccount(existing.getId(), update(null, null));

        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getDisplayOrder()).isEqualTo(7);
        assertThat(saved.getName()).isEqualTo("Renamed");
    }

    @Test
    void deleteAccount_systemAccount_throwsBusinessRuleViolation() {
        Account system = account(true);
        when(repository.findById(system.getId())).thenReturn(Optional.of(system));

        assertThatThrownBy(() -> service.deleteAccount(system.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("System accounts cannot be deleted");
        verify(repository, never()).delete(any());
    }

    @Test
    void deleteAccount_withChildren_throwsBusinessRuleViolation() {
        Account parent = account(false);
        when(repository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(repository.existsByParent_Id(parent.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.deleteAccount(parent.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cannot delete account with child accounts");
        verify(repository, never()).delete(any());
    }

    /**
     * The in-use guard. v1 asked financial_transactions "any row for this
     * account?"; the ledger of record is now journal_lines, and an account a
     * mapping points at is in use even with nothing posted to it yet.
     *
     * <p>Each case also asserts {@code repository.delete} was never reached:
     * the guard has to fire BEFORE any delete or detach work, or a half-applied
     * delete is what the caller gets back with their 400.
     */
    @Test
    void deleteAccount_withPostedJournalLines_throwsAndDeletesNothing() {
        Account leaf = account(false);
        when(repository.findById(leaf.getId())).thenReturn(Optional.of(leaf));
        when(journalLineRepository.existsByAccount_Id(leaf.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.deleteAccount(leaf.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Account has posted journal lines or mappings");

        verify(repository, never()).delete(any());
        verify(repository, never()).save(any());
    }

    @Test
    void deleteAccount_referencedByAPropertyMapping_throwsAndDeletesNothing() {
        Account leaf = account(false);
        when(repository.findById(leaf.getId())).thenReturn(Optional.of(leaf));
        when(journalLineRepository.existsByAccount_Id(leaf.getId())).thenReturn(false);
        when(propertyAccountMappingRepository.existsByAccount_Id(leaf.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.deleteAccount(leaf.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Account has posted journal lines or mappings");

        verify(repository, never()).delete(any());
        verify(repository, never()).save(any());
    }

    /**
     * tenant_default_account_mappings.account_id is NOT NULL under
     * fk_tdam_account (changeset 81). Leaving this table out of the guard did
     * not let the delete through — it turned a 400 carrying this message into a
     * raw constraint violation the caller had to decode.
     */
    @Test
    void deleteAccount_referencedByATenantDefaultMapping_throwsAndDeletesNothing() {
        Account leaf = account(false);
        when(repository.findById(leaf.getId())).thenReturn(Optional.of(leaf));
        when(journalLineRepository.existsByAccount_Id(leaf.getId())).thenReturn(false);
        when(propertyAccountMappingRepository.existsByAccount_Id(leaf.getId())).thenReturn(false);
        when(tenantDefaultAccountMappingRepository.existsByAccount_Id(leaf.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.deleteAccount(leaf.getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Account has posted journal lines or mappings");

        verify(repository, never()).delete(any());
        verify(repository, never()).save(any());
    }

    /** The other side of the guard: an unused, non-system leaf still deletes. */
    @Test
    void deleteAccount_unusedLeaf_deletes() {
        Account leaf = account(false);
        when(repository.findById(leaf.getId())).thenReturn(Optional.of(leaf));
        when(repository.existsByParent_Id(leaf.getId())).thenReturn(false);
        when(journalLineRepository.existsByAccount_Id(leaf.getId())).thenReturn(false);
        when(propertyAccountMappingRepository.existsByAccount_Id(leaf.getId())).thenReturn(false);
        when(tenantDefaultAccountMappingRepository.existsByAccount_Id(leaf.getId())).thenReturn(false);

        service.deleteAccount(leaf.getId());

        verify(repository).delete(leaf);
    }

    @Test
    void getAccountById_unknownId_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAccountById(id))
                .isInstanceOf(NotFoundException.class);
    }
}
