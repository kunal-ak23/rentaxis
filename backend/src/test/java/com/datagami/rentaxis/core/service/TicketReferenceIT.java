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
import com.datagami.rentaxis.testsupport.ChangesetSql;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

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
    /**
     * PR #342 review I3: changeset 95's backfill numbers existing tickets by the
     * Dubai calendar year, as runtime numbering does. created_at holds UTC wall
     * time, so 21:30 on 31 December UTC is 01:30 on 1 January in Dubai and
     * belongs to the new year's series.
     *
     * <p>Runs the changeset's own SQL against temporary tables that shadow the
     * real ones for this transaction only (pg_temp is searched first), which is
     * then rolled back: the shared test database is never renumbered.
     */
    @Test
    void theBackfillNumbersByTheDubaiYear() {
        UUID tenant = UUID.randomUUID();
        UUID lateDubaiNewYear = UUID.randomUUID();   // 2025-12-31 21:30 UTC = 2026-01-01 01:30 Dubai
        UUID lastOfTheYear = UUID.randomUUID();      // 2025-12-31 19:00 UTC = 2025-12-31 23:00 Dubai
        UUID secondOfJanuary = UUID.randomUUID();    // 2026-01-02 08:00 UTC

        tx.executeWithoutResult(status -> {
            jdbc.execute("CREATE TEMP TABLE maintenance_tickets "
                    + "(id uuid PRIMARY KEY, tenant_id uuid, created_at timestamp, reference varchar(20))");
            jdbc.execute("CREATE TEMP TABLE journal_entry_sequences "
                    + "(LIKE public.journal_entry_sequences INCLUDING ALL)");
            jdbc.update("INSERT INTO maintenance_tickets (id, tenant_id, created_at) VALUES (?, ?, ?::timestamp)",
                    lateDubaiNewYear, tenant, "2025-12-31 21:30:00");
            jdbc.update("INSERT INTO maintenance_tickets (id, tenant_id, created_at) VALUES (?, ?, ?::timestamp)",
                    lastOfTheYear, tenant, "2025-12-31 19:00:00");
            jdbc.update("INSERT INTO maintenance_tickets (id, tenant_id, created_at) VALUES (?, ?, ?::timestamp)",
                    secondOfJanuary, tenant, "2026-01-02 08:00:00");

            ChangesetSql.of("95-ticket-reference.yaml").forEach(jdbc::execute);

            assertThat(reference(lastOfTheYear)).isEqualTo("TKT-25/1");
            assertThat(reference(lateDubaiNewYear)).isEqualTo("TKT-26/1");
            assertThat(reference(secondOfJanuary)).isEqualTo("TKT-26/2");
            List<Map<String, Object>> seq = jdbc.queryForList(
                    "SELECT fiscal_year, next_value FROM journal_entry_sequences "
                            + "WHERE tenant_id = ? AND doc_type = 'TKT' ORDER BY fiscal_year", tenant);
            assertThat(seq).extracting(r -> ((Number) r.get("fiscal_year")).intValue(),
                            r -> ((Number) r.get("next_value")).longValue())
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(2025, 2L),
                            org.assertj.core.groups.Tuple.tuple(2026, 3L));
            status.setRollbackOnly();
        });
    }

    private String reference(UUID id) {
        return jdbc.queryForObject("SELECT reference FROM maintenance_tickets WHERE id = ?", String.class, id);
    }
}
