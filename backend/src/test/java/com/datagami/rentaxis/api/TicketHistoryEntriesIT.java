package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.CrossTenantHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tutorial 22: a ticket's activity history — presented as its full trail — had no
 * "created" entry, and setting the ETA left no trace.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TicketHistoryEntriesIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired PropertyService propertyService;

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void historyRecordsCreationAndEveryEtaChange() {
        CrossTenantHttp http = new CrossTenantHttp(port, orgRepo, userRepo, accountService, propertyAccountService, propertyService);
        UUID tenant = http.tenant("TicketHistory-");
        UUID propertyId = http.property("Oasis Crest").getId();
        User admin = http.admin(tenant);
        TenantContextHolder.clear();

        String id = (String) http.call(admin, HttpMethod.POST, "/api/v1/tickets",
                Map.of("propertyId", propertyId.toString(), "title", "AC not cooling")).getBody().get("id");
        http.call(admin, HttpMethod.PUT, "/api/v1/tickets/" + id + "/estimate", Map.of("hours", 4));
        http.call(admin, HttpMethod.PUT, "/api/v1/tickets/" + id + "/estimate", Map.of("hours", 4));
        http.call(admin, HttpMethod.PUT, "/api/v1/tickets/" + id + "/estimate", Map.of("hours", 6));

        List<Map> history = http.request(admin, HttpMethod.GET, "/api/v1/tickets/" + id + "/history")
                .retrieve().body(List.class);
        List<String> actions = history.stream().map(h -> (String) h.get("action")).toList();
        // Re-saving the same ETA is not a change.
        assertThat(actions).containsExactlyInAnyOrder("CREATED", "ETA_SET", "ETA_CHANGED");
        Map created = history.stream().filter(h -> "CREATED".equals(h.get("action"))).findFirst().orElseThrow();
        assertThat(created.get("performedBy")).isEqualTo(admin.getId().toString());
        Map changed = history.stream().filter(h -> "ETA_CHANGED".equals(h.get("action"))).findFirst().orElseThrow();
        assertThat(changed.get("notes")).isEqualTo("4→6");
    }
}
