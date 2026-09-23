package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The v1 portfolio upload records who started the import from the verified
 * principal (PR #342), not from X-User-Id, which on the bearer path is the
 * caller's to choose: an admin could attribute an import to a colleague.
 */
class PortfolioImportCallerIdentityIT extends AbstractCallerIdentityIT {

    private User adminA;
    private User victim;

    @BeforeEach
    void setUp() {
        UUID tenantId = newTenant("PCI");
        TenantContextHolder.setTenantId(tenantId);
        adminA = user(tenantId, UserRole.TENANT_ADMIN, "x");
        victim = user(tenantId, UserRole.TENANT_ADMIN, "x");
        TenantContextHolder.clear();
    }

    @Test
    void anImportStartedWithAForgedUserIdIsAttributedToTheCaller() {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("not really a workbook".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "portfolio.xlsx";
            }
        });

        @SuppressWarnings("rawtypes")
        Map started = forged(HttpMethod.POST, "/api/v1/import/portfolio", adminA, victim)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve().body(Map.class);

        UUID jobId = UUID.fromString((String) started.get("jobId"));
        assertThat(jdbc.queryForObject("SELECT created_by FROM import_jobs WHERE id = ?", UUID.class, jobId))
                .isEqualTo(adminA.getId());
    }
}
