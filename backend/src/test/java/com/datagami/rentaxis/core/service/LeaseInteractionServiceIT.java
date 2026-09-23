package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateInteractionRequest;
import com.datagami.rentaxis.api.dto.InteractionDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.InteractionDirection;
import com.datagami.rentaxis.domain.entity.enums.InteractionType;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug #42: same tenant-filtered {@code findById} name-resolution gap as
 * {@code MaintenanceTicketServiceIT}, here for the "created by" name on a lease
 * interaction. A SUPER_ADMIN acting inside a pivoted tenant has {@code tenant_id =
 * NULL}, which the tenant-filtered lookup can't see — so the fix is the same swap
 * to {@code UserRepository.findDisplayNameById}.
 */
@SpringBootTest
class LeaseInteractionServiceIT extends AbstractPostgresIT {

    @Autowired LeaseInteractionService interactions;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void aSuperAdminActingInsideAPivotedTenantKeepsTheirNameAsInteractionCreator() {
        UUID tenantId = tenant();
        UUID leaseId = leaseIn(tenantId);

        User superAdmin = new User();
        superAdmin.setName("Kunal (Platform Admin)");
        superAdmin.setEmail("platform-admin-" + UUID.randomUUID() + "@example.invalid");
        superAdmin.setPasswordHash("x");
        superAdmin.setRole(UserRole.SUPER_ADMIN);
        superAdmin.setTenantId(null);
        UUID superAdminId = userRepo.saveAndFlush(superAdmin).getId();

        TenantContextHolder.setTenantId(tenantId);
        CreateInteractionRequest req = new CreateInteractionRequest(
                InteractionType.NOTE, InteractionDirection.INTERNAL,
                Instant.now(), "Called renter about renewal", null, null);

        InteractionDTO dto = interactions.create(leaseId, req, superAdminId);

        assertThat(dto.createdBy()).isEqualTo(superAdminId);
        assertThat(dto.createdByName()).isEqualTo("Kunal (Platform Admin)");
    }

    private UUID tenant() {
        LandlordOrg org = new LandlordOrg();
        org.setName("T42-LI-" + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

    private UUID leaseIn(UUID tenantId) {
        UUID property = UUID.randomUUID(), unit = UUID.randomUUID(), renter = UUID.randomUUID();
        UUID lease = UUID.randomUUID();
        jdbc.update("INSERT INTO properties (id, tenant_id, name_en, emirate) VALUES (?,?,?,?)",
                property, tenantId, "P-" + property, "DUBAI");
        jdbc.update("INSERT INTO units (id, tenant_id, property_id, unit_number) VALUES (?,?,?,?)",
                unit, tenantId, property, "U-" + unit);
        jdbc.update("INSERT INTO renters (id, tenant_id, name_en) VALUES (?,?,?)", renter, tenantId, "R-" + renter);
        jdbc.update("INSERT INTO leases (id, tenant_id, unit_id, renter_id, start_date, end_date, status,"
                        + " rent_amount, deposit_amount) VALUES (?,?,?,?,?,?,?,?,?)",
                lease, tenantId, unit, renter, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                "ACTIVE", new BigDecimal("1200.00"), new BigDecimal("0.00"));
        return lease;
    }
}
