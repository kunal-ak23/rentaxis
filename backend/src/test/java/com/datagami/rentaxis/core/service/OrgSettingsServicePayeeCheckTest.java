package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.OrgSettings;
import com.datagami.rentaxis.domain.entity.enums.PayeeCheck;
import com.datagami.rentaxis.domain.repository.OrgSettingsRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The payee check reads the named organisation's settings row, by tenant id in
 * the query. In an IT the Hibernate tenant filter would hide a wrong row first,
 * so the explicit predicate is only observable here (the filter is off outside a
 * transaction or with no tenant in context).
 */
class OrgSettingsServicePayeeCheckTest {

    private final OrgSettingsRepository repo = mock(OrgSettingsRepository.class);
    private final OrgSettingsService service = new OrgSettingsService(repo);
    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();

    private OrgSettings settings(UUID tenant, boolean enabled, List<String> names) {
        OrgSettings s = new OrgSettings();
        s.setTenantId(tenant);
        s.setLandlordOrgId(tenant);
        s.setPayeeCheckEnabled(enabled);
        s.setValidPayeeNames(names);
        return s;
    }

    @Test
    void anotherOrganisationsListNeverApplies() {
        OrgSettings a = settings(orgA, true, List.of("Alpha Holdings"));
        when(repo.findByTenantIdOrderByIdAsc(orgA)).thenReturn(List.of(a));
        when(repo.findByTenantIdOrderByIdAsc(orgB)).thenReturn(List.of());
        when(repo.findAll()).thenReturn(List.of(a));

        assertThat(service.checkPayee(orgA, "ALPHA HOLDINGS")).isEqualTo(PayeeCheck.MATCH);
        assertThat(service.checkPayee(orgA, "Someone Else")).isEqualTo(PayeeCheck.MISMATCH);
        // B has no settings: no check, whatever A configured.
        assertThat(service.checkPayee(orgB, "Someone Else")).isNull();
        assertThat(service.checkPayee(orgB, "Alpha Holdings")).isNull();
        assertThat(service.checkPayee(null, "Alpha Holdings")).isNull();
    }

    @Test
    void offOrEmptyMeansNoCheckAndABlankPayeeIsUnreadable() {
        when(repo.findByTenantIdOrderByIdAsc(orgA)).thenReturn(List.of(settings(orgA, false, List.of("Alpha"))));
        assertThat(service.checkPayee(orgA, "Beta")).isNull();

        when(repo.findByTenantIdOrderByIdAsc(orgA)).thenReturn(List.of(settings(orgA, true, List.of())));
        assertThat(service.checkPayee(orgA, "Beta")).isNull();

        when(repo.findByTenantIdOrderByIdAsc(orgA)).thenReturn(List.of(settings(orgA, true, List.of("Alpha"))));
        assertThat(service.checkPayee(orgA, null)).isEqualTo(PayeeCheck.UNREADABLE);
        assertThat(service.checkPayee(orgA, "  ")).isEqualTo(PayeeCheck.UNREADABLE);
    }
}
