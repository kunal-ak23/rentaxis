package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
class AccountServiceTreeIT extends AbstractPostgresIT {

    @Autowired AccountService service;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PropertyRepository propertyRepo;

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
        assertThat(service.getChildren(equity.getId())).extracting(Account::getCode).containsExactly("F-01", "F-02", "F-03");
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
        service.createAccount(imported, null);
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
        group = service.createAccount(group, null);
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
        assertThatThrownBy(() -> service.createAccount(child, null))
                .hasMessageContaining("Parent account must be a group account");
    }

    private Property property(String name) {
        Property p = new Property();
        p.setNameEn(name);
        p.setEmirate(Emirate.DUBAI);
        return propertyRepo.save(p);
    }

    @Test
    void createLeafTagsTheLeafWithARealProperty() {
        service.seedDefaultAccounts();
        Account parent = service.getAccountByCode("A-02-01");
        Property building = property("Tulip 7");

        Account leaf = service.createLeaf("Rent Receivable - Tulip 7", parent, building.getId());

        assertThat(leaf.getPropertyId()).isEqualTo(building.getId());
    }

    /**
     * The property id arrives from a request body. Resolving it through the
     * tenant-filtered repository is what makes an id the caller invented — or
     * borrowed from another tenant — a 404 instead of a row tagged with a
     * building this tenant cannot see.
     */
    @Test
    void createLeafWithAnUnknownPropertyIsNotFound() {
        service.seedDefaultAccounts();
        Account parent = service.getAccountByCode("A-02-01");
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> service.createLeaf("Rent Receivable - nowhere", parent, stranger))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Property not found");
    }

    @Test
    void createAccountWithAnUnknownPropertyIsNotFound() {
        service.seedDefaultAccounts();
        Account a = new Account();
        a.setCode("Z-03");
        a.setName("Tagged with nothing");
        a.setAccountType(AccountType.EXPENSE);

        UUID stranger = UUID.randomUUID();
        assertThatThrownBy(() -> service.createAccount(a, stranger))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Property not found");
    }

    @Test
    void updateAppliesPropertyIdAndLeavesOmittedFlagsAlone() {
        service.seedDefaultAccounts();
        Account a = new Account();
        a.setCode("Z-04");
        a.setName("Editable");
        a.setAccountType(AccountType.EXPENSE);
        a = service.createAccount(a, null);
        a.setDisplayOrder(5);
        Property building = property("Olivier");

        Account tagged = service.updateAccount(a.getId(), new AccountService.AccountUpdate(
                "Editable", null, null, null, null, null, null, null, building.getId()));
        assertThat(tagged.getPropertyId()).isEqualTo(building.getId());
        assertThat(tagged.isActive()).isTrue();

        // propertyId is always applied, so a null clears the tag.
        Account untagged = service.updateAccount(a.getId(), new AccountService.AccountUpdate(
                "Editable", null, null, null, null, null, null, null, null));
        assertThat(untagged.getPropertyId()).isNull();
    }

    @Test
    void updateWithAnUnknownPropertyIsNotFound() {
        service.seedDefaultAccounts();
        Account a = new Account();
        a.setCode("Z-05");
        a.setName("Editable");
        a.setAccountType(AccountType.EXPENSE);
        Account created = service.createAccount(a, null);
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> service.updateAccount(created.getId(), new AccountService.AccountUpdate(
                "Editable", null, null, null, null, null, null, null, stranger)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Property not found");
    }
}
