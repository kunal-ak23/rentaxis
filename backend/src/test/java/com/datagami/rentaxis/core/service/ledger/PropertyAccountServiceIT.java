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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class PropertyAccountServiceIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

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

    @Test
    void creatingAPropertyGeneratesItsAccountSetAndMappings() {
        Property p = newProperty("Sample Plaza Oasis 7");
        List<RoleMappingDTO> m = service.getMappings(p.getId());
        RoleMappingDTO rr = m.stream().filter(x -> x.role() == AccountRole.RENT_RECEIVABLE).findFirst().orElseThrow();
        assertThat(rr.accountName()).isEqualTo("Rent Receivable - Sample Plaza Oasis 7");
        assertThat(rr.inherited()).isFalse();
        Account leaf = accountRepo.findById(rr.accountId()).orElseThrow();
        assertThat(accountRepo.findById(leaf.getParentId()).orElseThrow().getCode()).isEqualTo("A-02-01");
        assertThat(leaf.getAccountType()).isEqualTo(AccountType.ASSET);
        assertThat(leaf.getPropertyId()).isEqualTo(p.getId());
        assertThat(resolver.resolve(AccountRole.ADVANCE_RENT, p.getId()).getName()).isEqualTo("Advance Rent - Sample Plaza Oasis 7");
    }

    @Test
    void generateMissingIsIdempotentAndFillsOnlyGaps() {
        Property p = newProperty("Sample Vista");
        int before = accountRepo.findByProperty_Id(p.getId()).size();
        service.clearMapping(p.getId(), AccountRole.BANK);
        service.generateMissing(p.getId());
        // the existing "Emirates Islamic - Sample Vista" leaf is reused, not duplicated
        assertThat(accountRepo.findByProperty_Id(p.getId())).hasSize(before);
        assertThat(resolver.resolve(AccountRole.BANK, p.getId()).getName()).isEqualTo("Emirates Islamic - Sample Vista");
    }

    @Test
    void manualMappingToASharedAccountAndTypeCheck() {
        Property sampleResidences = newProperty("Sample Residences 2");
        Account generic = accounts.createLeaf("Rent Receivable", accounts.getAccountByCode("A-02-01"), null);
        service.setMapping(sampleResidences.getId(), AccountRole.RENT_RECEIVABLE, generic.getId());
        assertThat(resolver.resolve(AccountRole.RENT_RECEIVABLE, sampleResidences.getId()).getId()).isEqualTo(generic.getId());
        Account income = accounts.getAccountByCode("C-01-02-001");
        assertThatThrownBy(() -> service.setMapping(sampleResidences.getId(), AccountRole.RENT_RECEIVABLE, income.getId()))
                .hasMessageContaining("ASSET");
        Account group = accounts.getAccountByCode("A-02-01");
        assertThatThrownBy(() -> service.setMapping(sampleResidences.getId(), AccountRole.RENT_RECEIVABLE, group.getId()))
                .hasMessageContaining("group");
    }

    @Test
    void disabledTemplateRowIsSkipped() {
        var rows = service.getTemplate();
        service.saveTemplate(rows.stream().map(r -> r.role() == AccountRole.COOLING_CHARGES ? r.withEnabled(false) : r).toList());
        Property p = newProperty("Sample Atrium 10");
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
