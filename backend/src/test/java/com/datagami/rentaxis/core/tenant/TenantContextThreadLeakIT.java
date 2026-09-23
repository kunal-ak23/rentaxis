package com.datagami.rentaxis.core.tenant;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security audit 2026-09-24, P1-1. The tenant is a ThreadLocal and Tomcat
 * reuses worker threads. {@code TenantInterceptor} (now deleted) set it from
 * an anonymous caller's {@code X-Tenant-ID} and cleared it only in
 * {@code postHandle}, which never runs when the handler throws; nothing else
 * cleared it on the public routes. The next request on that thread — a login,
 * say — then ran with the attacker's tenant pinned on the Hibernate filter.
 *
 * <p>MockMvc executes each request on the calling thread, so consecutive
 * {@code perform} calls here are exactly "the next request on the same worker
 * thread".
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class TenantContextThreadLeakIT extends AbstractPostgresIT {

    private static final String PASSWORD = "thread-leak-it-password";

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PasswordEncoder passwordEncoder;

    private UUID victimTenant;
    private User user;

    @BeforeEach
    void setUp() {
        victimTenant = newTenant("LEAK-VICTIM");
        UUID homeTenant = newTenant("LEAK-HOME");
        TenantContextHolder.setTenantId(homeTenant);
        User u = new User();
        u.setEmail("leak-" + UUID.randomUUID() + "@t.io");
        u.setName("Leak Test");
        u.setRole(UserRole.TENANT_ADMIN);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash(passwordEncoder.encode(PASSWORD));
        u.setTenantId(homeTenant);
        user = userRepo.save(u);
        TenantContextHolder.clear();
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    private UUID newTenant(String prefix) {
        LandlordOrg org = new LandlordOrg();
        org.setName(prefix + "-" + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

    private void loginSucceeds() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aFailedAnonymousRequestDoesNotLeaveItsTenantForTheNextRequestOnTheThread() throws Exception {
        // The audit's exploit: an anonymous public call naming the victim tenant,
        // with a body that makes argument resolution throw.
        mvc.perform(post("/api/v1/public/renewal-intent")
                        .header("X-Tenant-ID", victimTenant.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json"))
                .andExpect(status().is4xxClientError());

        assertThat(TenantContextHolder.getTenantId())
                .as("nothing from that request may remain on the thread").isNull();

        // Before the fix this login ran with the victim tenant on the filter, found
        // no user outside it, and answered 401: a platform-wide login outage.
        loginSucceeds();
    }

    @Test
    void anUnknownPublicRouteWithATenantHeaderLeavesNothingBehind() throws Exception {
        mvc.perform(get("/api/v1/public/no-such-route").header("X-Tenant-ID", victimTenant.toString()));
        assertThat(TenantContextHolder.getTenantId()).isNull();
        loginSucceeds();
    }

    @Test
    void aThreadThatSomehowCarriesATenantStartsTheNextRequestClean() throws Exception {
        // Whatever put it there, a request must not inherit it.
        TenantContextHolder.setTenantId(victimTenant);
        loginSucceeds();
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }
}
