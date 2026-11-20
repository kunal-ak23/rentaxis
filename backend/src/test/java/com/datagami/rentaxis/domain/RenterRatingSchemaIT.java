package com.datagami.rentaxis.domain;

import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.domain.entity.RenterRating;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.repository.RenterRatingRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RenterRatingSchemaIT extends AbstractPostgresIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired RenterRatingRepository ratings;
    @Autowired TenantFeatureService tenantFeatureService;

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO landlord_org (id, name, slug) VALUES (?, ?, ?)", id, "T-" + id, "t-" + id);
        return id;
    }

    private UUID renter(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO renters (id, tenant_id, name_en) VALUES (?,?,?)", id, tenant, "R-" + id);
        return id;
    }

    private RenterRating rating(UUID tenant, UUID renter) {
        RenterRating r = new RenterRating();
        r.setTenantId(tenant);
        r.setRenterId(renter);
        r.setScore(82);
        r.setGrade(com.datagami.rentaxis.domain.entity.enums.RatingGrade.B);
        r.setDeductionsCount(1);
        r.setEventsJson("[]");
        r.setFingerprint("abc");
        r.setComputedAt(Instant.now());
        return r;
    }

    @Test
    void savesAndReadsRating() {
        UUID t = tenant();
        UUID renter = renter(t);
        ratings.saveAndFlush(rating(t, renter));
        RenterRating found = ratings.findByTenantIdAndRenterId(t, renter).orElseThrow();
        assertThat(found.getScore()).isEqualTo(82);
        assertThat(found.getGrade().name()).isEqualTo("B");
        assertThat(found.getEventsJson()).isEqualTo("[]");
    }

    @Test
    void uniquePerRenter() {
        UUID t = tenant();
        UUID renter = renter(t);
        ratings.saveAndFlush(rating(t, renter));
        assertThatThrownBy(() -> ratings.saveAndFlush(rating(t, renter)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void featureDefaultsOff() {
        assertThat(TenantFeature.RENTER_RATING.isDefaultEnabled()).isFalse();
        assertThat(tenantFeatureService.isEnabled(tenant(), TenantFeature.RENTER_RATING)).isFalse();
    }
}
