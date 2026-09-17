package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.PropertyAccountMapping;
import com.datagami.rentaxis.domain.entity.TenantDefaultAccountMapping;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.repository.PropertyAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.TenantDefaultAccountMappingRepository;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountResolverTest {

    PropertyAccountMappingRepository propertyRepo = mock(PropertyAccountMappingRepository.class);
    TenantDefaultAccountMappingRepository defaultRepo = mock(TenantDefaultAccountMappingRepository.class);
    AccountResolver resolver = new AccountResolver(propertyRepo, defaultRepo);
    UUID property = UUID.randomUUID();

    private Account acct(String code) { Account a = new Account(); a.setId(UUID.randomUUID()); a.setCode(code); a.setActive(true); return a; }
    private PropertyAccountMapping pm(AccountRole r, Account a) { PropertyAccountMapping m = new PropertyAccountMapping(); m.setPropertyId(property); m.setRole(r); m.setAccount(a); return m; }
    private TenantDefaultAccountMapping dm(AccountRole r, Account a) { TenantDefaultAccountMapping m = new TenantDefaultAccountMapping(); m.setRole(r); m.setAccount(a); return m; }

    @Test
    void propertyMappingWins() {
        Account prop = acct("166269"), def = acct("105590");
        when(propertyRepo.findByPropertyIdAndRole(property, AccountRole.RENT_RECEIVABLE)).thenReturn(Optional.of(pm(AccountRole.RENT_RECEIVABLE, prop)));
        when(defaultRepo.findByRole(AccountRole.RENT_RECEIVABLE)).thenReturn(Optional.of(dm(AccountRole.RENT_RECEIVABLE, def)));
        assertThat(resolver.resolve(AccountRole.RENT_RECEIVABLE, property).getCode()).isEqualTo("166269");
    }

    @Test
    void fallsBackToTenantDefault() {
        Account def = acct("105590");
        when(propertyRepo.findByPropertyIdAndRole(property, AccountRole.RENT_RECEIVABLE)).thenReturn(Optional.empty());
        when(defaultRepo.findByRole(AccountRole.RENT_RECEIVABLE)).thenReturn(Optional.of(dm(AccountRole.RENT_RECEIVABLE, def)));
        assertThat(resolver.resolve(AccountRole.RENT_RECEIVABLE, property).getCode()).isEqualTo("105590");
    }

    @Test
    void nullPropertyUsesTenantDefaultOnly() {
        Account def = acct("C-01");
        when(defaultRepo.findByRole(AccountRole.OUTPUT_VAT)).thenReturn(Optional.of(dm(AccountRole.OUTPUT_VAT, def)));
        assertThat(resolver.resolve(AccountRole.OUTPUT_VAT, null).getCode()).isEqualTo("C-01");
    }

    @Test
    void unmappedRoleFailsLoudly() {
        when(propertyRepo.findByPropertyIdAndRole(any(), any())).thenReturn(Optional.empty());
        when(defaultRepo.findByRole(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolve(AccountRole.ADVANCE_RENT, property))
                .isInstanceOf(UnmappedAccountRoleException.class)
                .hasMessageContaining("ADVANCE_RENT");
    }

    @Test
    void inactiveAccountIsTreatedAsUnmapped() {
        Account dead = acct("1"); dead.setActive(false);
        when(propertyRepo.findByPropertyIdAndRole(property, AccountRole.BANK)).thenReturn(Optional.of(pm(AccountRole.BANK, dead)));
        when(defaultRepo.findByRole(AccountRole.BANK)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolve(AccountRole.BANK, property)).isInstanceOf(UnmappedAccountRoleException.class);
    }

    @Test
    void resolveAllReportsEveryMissingRoleAtOnce() {
        Account rr = acct("1");
        when(propertyRepo.findByPropertyIdAndRoleIn(eq(property), any())).thenReturn(List.of(pm(AccountRole.RENT_RECEIVABLE, rr)));
        when(defaultRepo.findByRoleIn(any())).thenReturn(List.of());
        assertThatThrownBy(() -> resolver.resolveAll(EnumSet.of(AccountRole.RENT_RECEIVABLE, AccountRole.PDC_RECEIVABLE, AccountRole.BANK), property))
                .isInstanceOf(UnmappedAccountRoleException.class)
                .satisfies(e -> assertThat(((UnmappedAccountRoleException) e).getMissingRoles())
                        .containsExactlyInAnyOrder(AccountRole.PDC_RECEIVABLE, AccountRole.BANK));
    }
}
