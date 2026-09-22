package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.dto.ledger.RoleMappingDTO;
import com.datagami.rentaxis.core.service.*;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class SilentAccountsIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired VendorService vendors;
    @Autowired BankAccountService bankAccounts;
    @Autowired PropertyService properties;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired AccountResolver resolver;
    @Autowired LandlordOrgRepository orgRepo;

    @BeforeEach void tenant() {
        LandlordOrg org = new LandlordOrg(); org.setName("SA-" + UUID.randomUUID());
        TenantContextHolder.setTenantId(orgRepo.save(org).getId());
        accounts.seedDefaultAccounts(); propertyAccounts.seedDefaultTemplateAndDefaults();
    }
    @AfterEach void clear() { TenantContextHolder.clear(); }

    @Test
    void vendorGetsALeafUnderVendorsGroupNamedAfterIt() {
        Vendor v = new Vendor(); v.setNameEn("HAPPY LIVING PEST CONTROL SERVICES");
        v = vendors.createVendor(v);
        assertThat(v.getPayableAccount()).isNotNull();
        assertThat(v.getPayableAccount().getName()).isEqualTo("HAPPY LIVING PEST CONTROL SERVICES");
        assertThat(v.getPayableAccount().getParent().getCode()).isEqualTo("B-01-04");
        assertThat(v.getPayableAccount().isGroup()).isFalse();
    }

    @Test
    void renamingAndDeactivatingAVendorFollowsThroughToItsLeaf() {
        Vendor v = new Vendor(); v.setNameEn("Old Name"); v = vendors.createVendor(v);
        Vendor upd = new Vendor(); upd.setNameEn("New Name"); upd.setActive(false);
        v = vendors.updateVendor(v.getId(), upd);
        assertThat(v.getPayableAccount().getName()).isEqualTo("New Name");
        assertThat(v.getPayableAccount().isActive()).isFalse();
    }

    @Test
    void bankAccountOnAPropertyDefaultsToThePropertyBankLeaf() {
        Property p = new Property(); p.setNameEn("Sample Palm 2"); p.setEmirate(Emirate.DUBAI); p = properties.createProperty(p);
        BankAccount b = new BankAccount(); b.setBankName("Emirates Islamic"); b.setAccountNumber("1234567890"); b.setProperty(p);
        b = bankAccounts.createBankAccount(b);
        assertThat(b.getCoaAccount().getId()).isEqualTo(resolver.resolve(AccountRole.BANK, p.getId()).getId());
    }

    @Test
    void bankAccountWithoutPropertyGetsItsOwnLeaf() {
        BankAccount b = new BankAccount(); b.setBankName("ENBD"); b.setAccountNumber("9988776655");
        b = bankAccounts.createBankAccount(b);
        assertThat(b.getCoaAccount().getName()).isEqualTo("ENBD - 6655");
        assertThat(b.getCoaAccount().getParent().getCode()).isEqualTo("A-02-02");
    }

    @Test
    void propertyCreatedThroughBulkImportGetsItsAccountSet() throws Exception {
        String csv = "Building,UnitNumber,UnitType,SizeSqft,ExpectedRent,Status\n"
                + ",U-101,STUDIO,800,50000,VACANT\n";
        MockMultipartFile file = new MockMultipartFile("file", "units.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        var result = properties.importPropertyWithUnits(file, "Marina Vista Imported", null, "DUBAI",
                "Dubai Marina", "RESIDENTIAL", null);

        assertThat(result.getErrors()).isNullOrEmpty();
        List<RoleMappingDTO> mappings = propertyAccounts.getMappings(result.getPropertyId());
        RoleMappingDTO rr = mappings.stream().filter(m -> m.role() == AccountRole.RENT_RECEIVABLE).findFirst().orElseThrow();
        assertThat(rr.accountId()).isNotNull();
    }
}
