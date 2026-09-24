package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Vendor;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F14-43: one vendor per TRN and per name; deleting an unused vendor removes its payable leaf. */
@SpringBootTest
class VendorDuplicateIT extends AbstractPostgresIT {

    @Autowired VendorService vendors;
    @Autowired AccountService accounts;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("VDUP-" + UUID.randomUUID());
        TenantContextHolder.setTenantId(orgRepo.save(org).getId());
        accounts.seedDefaultAccounts();
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    private static Vendor vendor(String name, String trn) {
        Vendor v = new Vendor();
        v.setNameEn(name);
        v.setTrn(trn);
        return v;
    }

    @Test
    void aSecondVendorWithTheSameTrnOrNameIsRefused() {
        Vendor first = vendors.createVendor(vendor("R14 Sparkle Cleaning LLC", "100111222300003"));
        assertThatThrownBy(() -> vendors.createVendor(vendor("Another Name", "100 111 222 300 003")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already has TRN 100111222300003");
        assertThatThrownBy(() -> vendors.createVendor(vendor("r14 sparkle  cleaning, L.L.C.", null)))
                .hasMessageContaining("already exists");
        Vendor other = vendors.createVendor(vendor("Gulf Cool", null));
        assertThatThrownBy(() -> vendors.updateVendor(other.getId(), vendor("R14 SPARKLE CLEANING LLC", null)))
                .hasMessageContaining("already exists");
        // Saving itself unchanged is not a duplicate.
        assertThat(vendors.updateVendor(first.getId(), vendor("R14 Sparkle Cleaning LLC", "100111222300003")).getId())
                .isEqualTo(first.getId());
    }

    @Test
    void deletingAnUnusedVendorRemovesItsPayableLeaf() {
        Vendor v = vendors.createVendor(vendor("Temp Vendor", null));
        UUID leaf = v.getPayableAccount().getId();
        vendors.deleteVendor(v.getId());
        assertThat(jdbc.queryForObject("select count(*) from accounts where id = ? and is_active", Integer.class, leaf))
                .isZero();
    }
}
