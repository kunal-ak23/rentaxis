package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The chart of accounts against a real database: the seed has to produce a tree
 * joined by parent_id (the foreign key is checked on insert, so a parent that is
 * written after its child fails outright), and the numeric leaf-code sequence
 * has to survive an import that already used codes above where it would start.
 */
@SpringBootTest
@Testcontainers
class AccountServiceTreeIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired AccountService service;
    @Autowired LandlordOrgRepository orgRepo;

    UUID tenantId;

    @BeforeEach
    void tenant() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Acct-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void seedBuildsTreeByParentId() {
        service.seedDefaultAccounts();
        Account rentalReceivable = service.getAccountByCode("A-02-01");
        assertThat(rentalReceivable.getParentId()).isNotNull();
        assertThat(service.getAccountById(rentalReceivable.getParentId()).getCode()).isEqualTo("A-02");

        // getTree() is the roots; each level is fetched by parentId from there.
        assertThat(service.getTree()).extracting(Account::getCode).containsExactly("A", "B", "C", "D", "F");
        Account assets = service.getAccountByCode("A");
        assertThat(service.getChildren(assets.getId())).extracting(Account::getCode).containsExactly("A-01", "A-02");
        Account equity = service.getAccountByCode("F");
        assertThat(service.getChildren(equity.getId())).extracting(Account::getCode).containsExactly("F-01", "F-02");
    }

    @Test
    void nextLeafCodeStartsAt100001ThenIncrements() {
        service.seedDefaultAccounts();
        assertThat(service.nextLeafCode()).isEqualTo("100001");
        assertThat(service.nextLeafCode()).isEqualTo("100002");
    }

    @Test
    void nextLeafCodeContinuesAboveImportedNumericCodes() {
        service.seedDefaultAccounts();
        Account parent = service.getAccountByCode("A-02-01");
        Account imported = new Account();
        imported.setCode("166269");
        imported.setName("Rent Receivable - L'Olivier");
        imported.setAccountType(AccountType.ASSET);
        imported.setParent(parent);
        service.createAccount(imported);
        assertThat(service.nextLeafCode()).isEqualTo("166270");
    }

    @Test
    void createLeafUnderParentInheritsTypeAndGetsSequentialCode() {
        service.seedDefaultAccounts();
        Account parent = service.getAccountByCode("A-02-01");
        Account leaf = service.createLeaf("Rent Receivable - Tulip 7", parent, null);
        assertThat(leaf.getCode()).isEqualTo("100001");
        assertThat(leaf.getAccountType()).isEqualTo(AccountType.ASSET);
        assertThat(leaf.getParentId()).isEqualTo(parent.getId());
        assertThat(leaf.isGroup()).isFalse();
    }

    @Test
    void cannotDeleteAccountWithChildren() {
        service.seedDefaultAccounts();
        Account a02 = service.getAccountByCode("A-02");
        // seeded accounts are system; create a non-system group with a child to test the rule
        Account group = new Account();
        group.setCode("Z-01");
        group.setName("Custom group");
        group.setAccountType(AccountType.EXPENSE);
        group.setGroup(true);
        group = service.createAccount(group);
        service.createLeaf("Custom leaf", group, null);
        UUID groupId = group.getId();
        assertThatThrownBy(() -> service.deleteAccount(groupId)).hasMessageContaining("child accounts");
        assertThat(a02.isGroup()).isTrue();
    }

    @Test
    void createAccountRejectsALeafAsParent() {
        service.seedDefaultAccounts();
        Account leafParent = service.getAccountByCode("F-01");
        Account child = new Account();
        child.setCode("Z-02");
        child.setName("Under a leaf");
        child.setParent(leafParent);
        assertThatThrownBy(() -> service.createAccount(child))
                .hasMessageContaining("Parent account must be a group account");
    }
}
