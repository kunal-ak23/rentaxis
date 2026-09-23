package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateTicketDTO;
import com.datagami.rentaxis.api.dto.MaintenanceTicketDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.MaintenanceTicketRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #19: "on behalf of" is a renter picked from the organisation's renters, stored
 * by id, resolved inside the ticket's tenant; the legacy text column carries the
 * name so older readers keep working.
 */
@SpringBootTest
class TicketOnBehalfOfRenterIT extends AbstractPostgresIT {

    @Autowired MaintenanceTicketService tickets;
    @Autowired MaintenanceTicketRepository ticketRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired LandlordOrgRepository orgRepo;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UUID.randomUUID().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private UUID tenant() {
        LandlordOrg org = new LandlordOrg();
        org.setName("OBO-" + UUID.randomUUID());
        UUID id = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(id);
        return id;
    }

    private UUID property() {
        Property p = new Property();
        p.setNameEn("Tower " + UUID.randomUUID());
        p.setEmirate(Emirate.DUBAI);
        return propertyRepo.save(p).getId();
    }

    private Renter renter(String name) {
        Renter r = new Renter();
        r.setNameEn(name);
        return renterRepo.save(r);
    }

    private CreateTicketDTO dto(UUID propertyId, UUID renterId) {
        CreateTicketDTO dto = new CreateTicketDTO();
        dto.setPropertyId(propertyId);
        dto.setTitle("Noise from 1204");
        dto.setOnBehalfOf("typed text that the pick replaces");
        dto.setOnBehalfOfRenterId(renterId);
        return dto;
    }

    @Test
    void staffLogATicketForAPickedRenter() {
        tenant();
        as("PROPERTY_MANAGER");
        UUID propertyId = property();
        Renter rajesh = renter("Rajesh Kumar");

        MaintenanceTicketDTO created = tickets.createTicket(dto(propertyId, rajesh.getId()), UUID.randomUUID());

        assertThat(created.getOnBehalfOfRenterId()).isEqualTo(rajesh.getId());
        assertThat(created.getOnBehalfOf()).isEqualTo("Rajesh Kumar");
        assertThat(ticketRepo.findById(created.getId()).orElseThrow().getOnBehalfOfRenterId()).isEqualTo(rajesh.getId());
    }

    @Test
    void anotherLandlordsRenterIsNotFound() {
        tenant();
        UUID foreignRenter = renter("Someone Else").getId();
        tenant();
        as("TENANT_ADMIN");
        UUID propertyId = property();

        assertThatThrownBy(() -> tickets.createTicket(dto(propertyId, foreignRenter), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aRenterCannotLogForAnotherRenter() {
        tenant();
        UUID propertyId = property();
        UUID other = renter("Neighbour").getId();
        as("RENTER");

        assertThatThrownBy(() -> tickets.createTicket(dto(propertyId, other), UUID.randomUUID()))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void freeTextAloneStillWorksForLegacyCallers() {
        tenant();
        as("PROPERTY_MANAGER");
        MaintenanceTicketDTO created = tickets.createTicket(dto(property(), null), UUID.randomUUID());

        assertThat(created.getOnBehalfOfRenterId()).isNull();
        assertThat(created.getOnBehalfOf()).isEqualTo("typed text that the pick replaces");
    }
}
