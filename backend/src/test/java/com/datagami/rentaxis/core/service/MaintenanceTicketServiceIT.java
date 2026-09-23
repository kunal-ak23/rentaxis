package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.MaintenanceTicket;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.TicketHistory;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.TicketCategory;
import com.datagami.rentaxis.domain.entity.enums.TicketPriority;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.MaintenanceTicketRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.TicketHistoryRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug #42: a SUPER_ADMIN acting inside a pivoted tenant has {@code tenant_id =
 * NULL}, so the tenant-filtered {@code UserRepository.findById} used to resolve
 * the actor's display name can't see them — the ticket-history row got saved with
 * {@code performedByName = null} and the UI showed "System" forever, because the
 * name is stamped onto the row at write time and never re-resolved.
 */
@SpringBootTest
class MaintenanceTicketServiceIT extends AbstractPostgresIT {

    @Autowired MaintenanceTicketService tickets;
    @Autowired MaintenanceTicketRepository ticketRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired TicketHistoryRepository historyRepo;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @org.junit.jupiter.api.BeforeEach
    void actAsStaff() {
        // The ticket service resolves the caller's reach (round 5): act as a tenant admin.
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        java.util.UUID.randomUUID().toString(), null,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    @Test
    void aSuperAdminActingInsideAPivotedTenantKeepsTheirNameOnTicketHistory() {
        UUID tenantId = tenant();

        TenantContextHolder.setTenantId(tenantId);
        Property property = new Property();
        property.setNameEn("Tower " + UUID.randomUUID());
        property.setEmirate(Emirate.DUBAI);
        UUID propertyId = propertyRepo.save(property).getId();

        MaintenanceTicket ticket = new MaintenanceTicket();
        ticket.setProperty(propertyRepo.findById(propertyId).orElseThrow());
        ticket.setReportedBy(UUID.randomUUID());
        ticket.setTitle("AC not cooling");
        ticket.setCategory(TicketCategory.PLUMBING);
        ticket.setPriority(TicketPriority.MEDIUM);
        UUID ticketId = ticketRepo.save(ticket).getId();

        // A platform SUPER_ADMIN: tenant_id is NULL by design (they read/act
        // across tenants), unlike every other role.
        User superAdmin = new User();
        superAdmin.setName("Kunal (Platform Admin)");
        superAdmin.setEmail("platform-admin-" + UUID.randomUUID() + "@example.invalid");
        superAdmin.setPasswordHash("x");
        superAdmin.setRole(UserRole.SUPER_ADMIN);
        superAdmin.setTenantId(null);
        UUID superAdminId = userRepo.saveAndFlush(superAdmin).getId();

        // Still pivoted into the tenant when the super admin acts — this is what
        // engages TenantAspect's Hibernate filter with tenantId, which the
        // buggy tenant-filtered findById(performedBy) could not see past.
        TenantContextHolder.setTenantId(tenantId);
        tickets.updateStatus(ticketId, "CLOSED", superAdminId);

        List<TicketHistory> history = historyRepo.findByTicketIdOrderByCreatedAtAsc(ticketId);
        assertThat(history).isNotEmpty();
        TicketHistory last = history.get(history.size() - 1);
        assertThat(last.getPerformedBy()).isEqualTo(superAdminId);
        assertThat(last.getPerformedByName()).isEqualTo("Kunal (Platform Admin)");
    }

    private UUID tenant() {
        LandlordOrg org = new LandlordOrg();
        org.setName("T42-" + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }
}
