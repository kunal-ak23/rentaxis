package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The import writes the tree by parent_id now, and parent_id is a real foreign
 * key: a child row saved before its parent fails the insert outright. A chart
 * of accounts exported from anywhere is free to list a child above its parent,
 * so the import resolves codes in a second pass and saves by depth.
 */
class AccountImportServiceTest {

    private AccountRepository repository;
    private AccountImportService service;

    @BeforeEach
    void setUp() {
        repository = mock(AccountRepository.class);
        when(repository.findByCode(anyString())).thenReturn(Optional.empty());
        when(repository.saveAll(anyIterable()))
                .thenAnswer(inv -> new ArrayList<Account>(inv.getArgument(0)));
        service = new AccountImportService(repository);
    }

    /** code,name,type,parentCode,nameAr,description,isGroup */
    private MockMultipartFile csv(String... rows) {
        StringBuilder sb = new StringBuilder("code,name,type,parentCode,nameAr,description,isGroup\n");
        for (String row : rows) {
            sb.append(row).append('\n');
        }
        return new MockMultipartFile("file", "coa.csv", "text/csv", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void childListedBeforeItsParentIsLinkedAndSavedAfterIt() throws Exception {
        List<Account> saved = service.importFromCsv(csv(
                "A-01-01,Rent Receivable,ASSET,A-01,,,false",
                "A-01,Current Assets,ASSET,A,,,true",
                "A,Assets,ASSET,,,,true"));

        assertThat(saved).extracting(Account::getCode)
                .containsExactly("A", "A-01", "A-01-01");
        Account leaf = saved.get(2);
        assertThat(leaf.getParent()).isSameAs(saved.get(1));
        assertThat(saved.get(1).getParent()).isSameAs(saved.get(0));
        assertThat(saved.get(0).getParent()).isNull();
    }

    @Test
    void parentAlreadyInTheTenantIsResolvedFromTheRepository() throws Exception {
        Account existing = new Account();
        existing.setId(UUID.randomUUID());
        existing.setCode("A-02");
        when(repository.findByCode("A-02")).thenReturn(Optional.of(existing));

        List<Account> saved = service.importFromCsv(csv("A-02-09,Rent Receivable,ASSET,A-02,,,false"));

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getParent()).isSameAs(existing);
    }

    @Test
    void unknownParentCodeNamesTheOffendingRow() {
        assertThatThrownBy(() -> service.importFromCsv(csv(
                "A,Assets,ASSET,,,,true",
                "A-01-01,Rent Receivable,ASSET,NOPE,,,false")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown parent code: NOPE on row 3");
    }

    @Test
    void importedAccountsAreNeverSystemAccounts() throws Exception {
        List<Account> saved = service.importFromCsv(csv("A,Assets,ASSET,,,,true"));

        assertThat(saved.get(0).isSystem()).isFalse();
        assertThat(saved.get(0).isGroup()).isTrue();
    }
}
