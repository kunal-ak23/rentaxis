package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.dto.ledger.RoleMappingDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PropertyAccountServiceIT extends AbstractPostgresIT {


    @Autowired PropertyAccountService service;
    @Autowired PropertyService properties;
    @Autowired AccountService accounts;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountResolver resolver;
    @Autowired LandlordOrgRepository orgRepo;

    @BeforeEach void tenant() {
        TenantContextHolder.setTenantId(newTenant("PA-"));
        accounts.seedDefaultAccounts();
        service.seedDefaultTemplateAndDefaults();
    }
    @AfterEach void clear() { TenantContextHolder.clear(); }

    private UUID newTenant(String prefix) {
        LandlordOrg org = new LandlordOrg(); org.setName(prefix + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

    private Property newProperty(String name) {
        Property p = new Property(); p.setNameEn(name); p.setEmirate(Emirate.DUBAI);
        return properties.createProperty(p);
    }

    @Test
    void seedCreatesTemplateRowsAndTenantDefaults() {
        assertThat(service.getTemplate()).extracting(r -> r.role()).contains(
                AccountRole.RENT_RECEIVABLE, AccountRole.ADVANCE_RENT, AccountRole.RENTAL_INCOME, AccountRole.PDC_RECEIVABLE,
                AccountRole.BANK, AccountRole.SECURITY_DEPOSIT, AccountRole.ADMIN_FEE, AccountRole.RENT_PENALTY, AccountRole.CHEQUE_RETURN_PENALTY);
        assertThat(resolver.resolve(AccountRole.CASH, null).getCode()).isEqualTo("A-02-05-001");
        assertThat(resolver.resolve(AccountRole.OUTPUT_VAT, null).getCode()).isEqualTo("B-01-03-001");
        assertThat(resolver.resolve(AccountRole.INPUT_VAT, null).getCode()).isEqualTo("A-02-04-001");
        assertThat(resolver.resolve(AccountRole.ROUNDING_OFF, null).getCode()).isEqualTo("D-02-001");
        assertThat(resolver.resolve(AccountRole.OPENING_BALANCE_DIFFERENCE, null).getCode()).isEqualTo("F-02");
    }

    /**
     * Gap #68: a generated leaf is named in Arabic too, from a per-role label plus the
     * property's Arabic name. Roles that share a group (Rental Income and Admin Fee
     * both sit under C-01-01) must not end up with the same Arabic name.
     */
    @Test
    void generatedLeavesGetAnArabicName() {
        Property draft = new Property(); draft.setNameEn("Miftah Residences"); draft.setNameAr("مفتاح ريزيدنسز");
        draft.setEmirate(Emirate.DUBAI);
        Property p = properties.createProperty(draft);
        assertThat(resolver.resolve(AccountRole.RENT_RECEIVABLE, p.getId()).getNameAr())
                .isEqualTo("إيجارات مستحقة - مفتاح ريزيدنسز");
        String income = resolver.resolve(AccountRole.RENTAL_INCOME, p.getId()).getNameAr();
        String adminFee = resolver.resolve(AccountRole.ADMIN_FEE, p.getId()).getNameAr();
        assertThat(income).isEqualTo("إيرادات الإيجار - مفتاح ريزيدنسز");
        assertThat(adminFee).isNotEqualTo(income).endsWith(" - مفتاح ريزيدنسز");
        // Direct-expense leaves, which carry no role, are named too.
        assertThat(accountRepo.findAll().stream()
                .filter(a -> p.getId().equals(a.getPropertyId()) && a.getName().startsWith("Cleaning - ")))
                .singleElement().extracting(Account::getNameAr).isEqualTo("التنظيف - مفتاح ريزيدنسز");
    }

    @Test
    void aPropertyWithNoArabicNameStillGetsAnArabicLabel() {
        Property p = newProperty("Tara 9");
        assertThat(resolver.resolve(AccountRole.RENT_RECEIVABLE, p.getId()).getNameAr())
                .isEqualTo("إيجارات مستحقة - Tara 9");
    }

    @Test
    void creatingAPropertyGeneratesItsAccountSetAndMappings() {
        Property p = newProperty("Tulip Oasis 7");
        List<RoleMappingDTO> m = service.getMappings(p.getId());
        RoleMappingDTO rr = m.stream().filter(x -> x.role() == AccountRole.RENT_RECEIVABLE).findFirst().orElseThrow();
        assertThat(rr.accountName()).isEqualTo("Rent Receivable - Tulip Oasis 7");
        assertThat(rr.inherited()).isFalse();
        Account leaf = accountRepo.findById(rr.accountId()).orElseThrow();
        assertThat(accountRepo.findById(leaf.getParentId()).orElseThrow().getCode()).isEqualTo("A-02-01");
        assertThat(leaf.getAccountType()).isEqualTo(AccountType.ASSET);
        assertThat(leaf.getPropertyId()).isEqualTo(p.getId());
        assertThat(resolver.resolve(AccountRole.ADVANCE_RENT, p.getId()).getName()).isEqualTo("Advance Rent - Tulip Oasis 7");
    }

    @Test
    void generateMissingIsIdempotentAndFillsOnlyGaps() {
        Property p = newProperty("Belle Vue");
        int before = accountRepo.findByProperty_Id(p.getId()).size();
        service.clearMapping(p.getId(), AccountRole.BANK);
        service.generateMissing(p.getId());
        // the existing "Emirates Islamic - Belle Vue" leaf is reused, not duplicated
        assertThat(accountRepo.findByProperty_Id(p.getId())).hasSize(before);
        assertThat(resolver.resolve(AccountRole.BANK, p.getId()).getName()).isEqualTo("Emirates Islamic - Belle Vue");
    }

    /**
     * D-01 describes itself as holding "building running costs, one leaf per
     * property per category" and used to ship with no children, which left a new
     * tenant with nothing but Rounding Off, Discount Allowed and Bank Charges to
     * code a supplier invoice to.
     */
    @Test
    void creatingAPropertyGeneratesItsDirectExpenseLeaves() {
        Property p = newProperty("Nakheel Court");
        Account directExpense = accounts.getAccountByCode("D-01");

        assertThat(accountRepo.findByProperty_Id(p.getId()))
                .filteredOn(a -> a.getParent() != null
                        && a.getParent().getId().equals(directExpense.getId()))
                .extracting(Account::getName)
                .contains("Repairs & Maintenance - Nakheel Court",
                        "Cleaning - Nakheel Court",
                        "Security - Nakheel Court",
                        "Utilities - Nakheel Court",
                        "Insurance - Nakheel Court",
                        "Management Fees - Nakheel Court");
        assertThat(accountRepo.findByProperty_Id(p.getId()))
                .filteredOn(a -> a.getName().startsWith("Repairs & Maintenance"))
                .singleElement()
                .satisfies(a -> assertThat(a.getAccountType()).isEqualTo(AccountType.EXPENSE));
    }

    /** A second generation run must not duplicate the expense leaves. */
    @Test
    void regeneratingDoesNotDuplicateDirectExpenseLeaves() {
        Property p = newProperty("Nakheel Court 2");
        int before = accountRepo.findByProperty_Id(p.getId()).size();

        service.generateMissing(p.getId());

        assertThat(accountRepo.findByProperty_Id(p.getId())).hasSize(before);
    }

    /**
     * A leaf under D-01 that belongs to nobody must not stop a property of the
     * same name getting its own six leaves.
     *
     * <p>Property names are unique per tenant ({@code ux_properties_tenant_name_en_lower}),
     * so two <em>live</em> properties can never share a {@code nameEn} within one
     * tenant — but an account's name is not similarly protected. A discarded
     * import batch clears {@code accounts.property_id} back to null and leaves
     * the row (and its name) behind — see
     * {@code ImportBatchDiscardService#deleteProperty} — so a leaf named
     * "Repairs & Maintenance - Nakheel Court" can already exist with no property
     * at all by the time a property newly named "Nakheel Court" is created. The
     * old dedup looked the leaf up by {@code (name, parent)} alone, found that
     * orphan, decided the category was "already generated" and created nothing
     * for the new property — which then had no leaf to code a supplier invoice
     * to, silently, with nothing in the response saying so.</p>
     */
    @Test
    void aLeafThatBelongsToNoPropertyDoesNotStopANewPropertyGettingItsOwn() {
        Account directExpense = accounts.getAccountByCode("D-01");
        String propertyName = "Nakheel Court Reimport " + UUID.randomUUID().toString().substring(0, 8);
        // A leftover from a discarded import batch: same name this property's
        // leaves will need, but property = null.
        Account orphan = accounts.createLeaf("Repairs & Maintenance - " + propertyName, directExpense, null);
        assertThat(orphan.getPropertyId()).isNull();

        Property p = newProperty(propertyName);

        List<Account> leaves = accountRepo.findByProperty_Id(p.getId()).stream()
                .filter(a -> a.getParent() != null && a.getParent().getId().equals(directExpense.getId())).toList();
        assertThat(leaves).hasSize(6);
        assertThat(leaves).extracting(Account::getId).doesNotContain(orphan.getId());
        assertThat(leaves).allSatisfy(a -> assertThat(a.getPropertyId()).isEqualTo(p.getId()));
    }

    /**
     * The dedup looked for any leaf of this property under D-01 whose name merely
     * starts with the category, so a hand-made "Security Deposit Refunds" leaf
     * passed for the generated "Security - X" and the property never got one.
     */
    @Test
    void aHandMadeLeafSharingACategorysFirstWordDoesNotStopItsLeaf() {
        Property p = newProperty("Nakheel Court Security");
        Account directExpense = accounts.getAccountByCode("D-01");
        removeGeneratedLeaf(p, "Security - Nakheel Court Security");
        accounts.createLeaf("Security Deposit Refunds", directExpense, p.getId());

        service.generateMissing(p.getId());

        assertThat(accountRepo.findByProperty_Id(p.getId())).extracting(Account::getName)
                .contains("Security - Nakheel Court Security", "Security Deposit Refunds");
    }

    /** Two such leaves made the single-result finder throw and the whole generation fail. */
    @Test
    void twoHandMadeLeavesSharingACategorysFirstWordDoNotBreakGeneration() {
        Property p = newProperty("Nakheel Court Guards");
        Account directExpense = accounts.getAccountByCode("D-01");
        removeGeneratedLeaf(p, "Security - Nakheel Court Guards");
        accounts.createLeaf("Security Deposit Refunds", directExpense, p.getId());
        accounts.createLeaf("Security Guard Wages", directExpense, p.getId());

        service.generateMissing(p.getId());

        assertThat(accountRepo.findByProperty_Id(p.getId())).extracting(Account::getName)
                .contains("Security - Nakheel Court Guards");
    }

    private void removeGeneratedLeaf(Property p, String name) {
        Account generated = accountRepo.findByProperty_Id(p.getId()).stream()
                .filter(a -> a.getName().equals(name)).findFirst().orElseThrow();
        accountRepo.delete(generated);
        assertThat(accountRepo.findByProperty_Id(p.getId())).extracting(Account::getName).doesNotContain(name);
    }

    @Test
    void manualMappingToASharedAccountAndTypeCheck() {
        Property galah = newProperty("Galah Residence 2");
        Account generic = accounts.createLeaf("Rent Receivable", accounts.getAccountByCode("A-02-01"), null);
        service.setMapping(galah.getId(), AccountRole.RENT_RECEIVABLE, generic.getId());
        assertThat(resolver.resolve(AccountRole.RENT_RECEIVABLE, galah.getId()).getId()).isEqualTo(generic.getId());
        Account income = accounts.getAccountByCode("C-01-02-001");
        assertThatThrownBy(() -> service.setMapping(galah.getId(), AccountRole.RENT_RECEIVABLE, income.getId()))
                .hasMessageContaining("ASSET");
        Account group = accounts.getAccountByCode("A-02-01");
        assertThatThrownBy(() -> service.setMapping(galah.getId(), AccountRole.RENT_RECEIVABLE, group.getId()))
                .hasMessageContaining("group");
    }

    @Test
    void disabledTemplateRowIsSkipped() {
        var rows = service.getTemplate();
        service.saveTemplate(rows.stream().map(r -> r.role() == AccountRole.COOLING_CHARGES ? r.withEnabled(false) : r).toList());
        Property p = newProperty("OST-10");
        RoleMappingDTO cooling = service.getMappings(p.getId()).stream().filter(x -> x.role() == AccountRole.COOLING_CHARGES).findFirst().orElseThrow();
        assertThat(cooling.accountId()).isNull();
    }

    /**
     * The three mapping tables extend BaseTenantEntity, but nothing yet proved the
     * Hibernate filter actually covers them: a generated mapping must be invisible —
     * and the role unresolvable — from a second tenant holding the same property id.
     */
    @Test
    void mappingsAndDefaultsAreInvisibleToAnotherTenant() {
        Property p = newProperty("Marina Heights");
        assertThat(resolver.resolve(AccountRole.RENT_RECEIVABLE, p.getId()).getName()).isEqualTo("Rent Receivable - Marina Heights");

        TenantContextHolder.setTenantId(newTenant("PA-other-"));
        assertThat(service.getMappings(p.getId())).isNotEmpty().allSatisfy(m -> assertThat(m.accountId()).isNull());
        assertThat(service.getTenantDefaults()).allSatisfy(m -> assertThat(m.accountId()).isNull());
        assertThatThrownBy(() -> resolver.resolve(AccountRole.RENT_RECEIVABLE, p.getId()))
                .isInstanceOf(UnmappedAccountRoleException.class);
        assertThatThrownBy(() -> resolver.resolve(AccountRole.CASH, null))
                .isInstanceOf(UnmappedAccountRoleException.class);
    }
}
