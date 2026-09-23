package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateTicketDTO;
import com.datagami.rentaxis.api.dto.MaintenanceTicketDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #20: every new ticket gets a human reference, "TKT-yy/n", counted per tenant
 * and calendar year, and it comes back on the list and the detail.
 */
@SpringBootTest
class TicketReferenceIT extends AbstractPostgresIT {

    @Autowired MaintenanceTicketService tickets;
    @Autowired PropertyRepository propertyRepo;
    @Autowired LandlordOrgRepository orgRepo;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private UUID propertyInNewTenant() {
        LandlordOrg org = new LandlordOrg();
        org.setName("TKT-" + UUID.randomUUID());
        UUID tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        Property p = new Property();
        p.setNameEn("Tower " + UUID.randomUUID());
        p.setEmirate(Emirate.DUBAI);
        return propertyRepo.save(p).getId();
    }

    private MaintenanceTicketDTO create(UUID propertyId, String title) {
        CreateTicketDTO dto = new CreateTicketDTO();
        dto.setPropertyId(propertyId);
        dto.setTitle(title);
        return tickets.createTicket(dto, UUID.randomUUID());
    }

    @Test
    void referencesRunPerTenantAndYearInCreationOrder() {
        String yy = String.format("%02d", LocalDate.now().getYear() % 100);

        UUID first = propertyInNewTenant();
        MaintenanceTicketDTO a1 = create(first, "Leak");
        MaintenanceTicketDTO a2 = create(first, "Noise");

        UUID second = propertyInNewTenant();
        MaintenanceTicketDTO b1 = create(second, "Lift");

        assertThat(a1.getReference()).isEqualTo("TKT-" + yy + "/1");
        assertThat(a2.getReference()).isEqualTo("TKT-" + yy + "/2");
        // Another landlord's counter is its own.
        assertThat(b1.getReference()).isEqualTo("TKT-" + yy + "/1");

        TenantContextHolder.setTenantId(orgOf(first));
        assertThat(tickets.getTicket(a2.getId(), null).getReference()).isEqualTo("TKT-" + yy + "/2");
    }

    private UUID orgOf(UUID propertyId) {
        TenantContextHolder.clear();
        return propertyRepo.findById(propertyId).orElseThrow().getTenantId();
    }
}
