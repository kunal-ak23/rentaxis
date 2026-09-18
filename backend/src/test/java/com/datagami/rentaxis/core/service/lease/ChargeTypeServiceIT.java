package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.lease.ChargeTypeDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class ChargeTypeServiceIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ChargeTypeService service;
    @Autowired LandlordOrgRepository orgRepo;

    @BeforeEach void tenant() {
        TenantContextHolder.setTenantId(newTenant());
        service.seedDefaults();
    }
    @AfterEach void clear() { TenantContextHolder.clear(); }

    private UUID newTenant() {
        LandlordOrg org = new LandlordOrg(); org.setName("CT-" + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

    private static ChargeTypeDTO dto(String code, AccountRole role, ChargeBehaviour behaviour) {
        return new ChargeTypeDTO(null, code, code + " name", null, role, behaviour, false, true, 90);
    }

    @Test
    void seedingTwiceLeavesTheSevenPactParticulars() {
        service.seedDefaults();
        assertThat(service.list(false)).hasSize(7)
                .extracting(ChargeTypeDTO::code)
                .containsExactly("RENT", "SECURITY_DEPOSIT", "ADMIN_FEE", "PARKING_DEPOSIT",
                        "COOLING", "PARKING_FEE", "MAINTENANCE");
    }

    /**
     * The role on a charge type is the credit side of the line, so RENT names the
     * unearned-rent liability rather than rental income (spec §6.1). If this ever
     * flips to {@code RENTAL_INCOME} the whole per-day recognition schedule stops
     * having anything to recognise.
     */
    @Test
    void rentCreditsAdvanceRentAndBehavesAsRent() {
        var rent = service.getByCode("RENT");
        assertThat(rent.getRole()).isEqualTo(AccountRole.ADVANCE_RENT);
        assertThat(rent.getBehaviour()).isEqualTo(ChargeBehaviour.RENT);
        assertThat(rent.getNameAr()).isNotBlank();

        var deposit = service.getByCode("PARKING_DEPOSIT");
        assertThat(deposit.getRole()).isEqualTo(AccountRole.PARKING_DEPOSIT);
        assertThat(deposit.getBehaviour()).isEqualTo(ChargeBehaviour.DEPOSIT);
    }

    /**
     * Uniqueness is the database's, not a read-then-write check, so the collision
     * arrives as a {@code DataIntegrityViolationException} —
     * {@code GlobalExceptionHandler.handleDataIntegrityViolation} renders that as
     * HTTP 409 with the constraint name.
     */
    @Test
    void aSecondChargeTypeWithTheSameCodeIsRefused() {
        assertThatThrownBy(() -> service.create(dto("RENT", AccountRole.ADVANCE_RENT, ChargeBehaviour.RENT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** The same code in a different tenant is not a collision. */
    @Test
    void eachTenantGetsItsOwnCatalogue() {
        TenantContextHolder.setTenantId(newTenant());
        assertThat(service.list(false)).isEmpty();
        service.seedDefaults();
        assertThat(service.list(false)).hasSize(7);
        assertThat(service.getByCode("RENT").getRole()).isEqualTo(AccountRole.ADVANCE_RENT);
    }

    @Test
    void behaviourAndCreditRoleMustAgree() {
        // a deposit credited to a fee income account is revenue that was never earned
        assertThatThrownBy(() -> service.create(dto("KEY_DEPOSIT", AccountRole.ADMIN_FEE, ChargeBehaviour.DEPOSIT)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("must credit a liability role");
        // and a fee credited to a deposit liability is a refundable that will never be refunded
        assertThatThrownBy(() -> service.create(dto("EJARI_FEE", AccountRole.SECURITY_DEPOSIT, ChargeBehaviour.FEE)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("must credit a non-liability role");
        // the RENT exception is narrow: it is ADVANCE_RENT specifically, not "any liability"
        assertThatThrownBy(() -> service.create(dto("SUBLET_RENT", AccountRole.SECURITY_DEPOSIT, ChargeBehaviour.RENT)))
                .isInstanceOf(BusinessRuleViolationException.class);

        // the exception itself, and the ordinary cases either side of it
        assertThat(service.create(dto("RENT_2", AccountRole.ADVANCE_RENT, ChargeBehaviour.RENT)).role())
                .isEqualTo(AccountRole.ADVANCE_RENT);
        assertThat(service.create(dto("KEY_DEPOSIT_2", AccountRole.PARKING_DEPOSIT, ChargeBehaviour.DEPOSIT)).behaviour())
                .isEqualTo(ChargeBehaviour.DEPOSIT);
        assertThat(service.create(dto("EJARI_2", AccountRole.OTHER_INCOME, ChargeBehaviour.FEE)).behaviour())
                .isEqualTo(ChargeBehaviour.FEE);
    }

    @Test
    void updateChangesTheCatalogueEntryButNeverItsCode() {
        UUID id = service.getByCode("COOLING").getId();
        ChargeTypeDTO edited = new ChargeTypeDTO(id, "COOLING", "District Cooling", "تبريد المنطقة",
                AccountRole.COOLING_CHARGES, ChargeBehaviour.FEE, true, false, 55);
        ChargeTypeDTO saved = service.update(id, edited);
        assertThat(saved.nameEn()).isEqualTo("District Cooling");
        assertThat(saved.vatApplicableDefault()).isTrue();
        assertThat(saved.active()).isFalse();
        assertThat(service.list(true)).extracting(ChargeTypeDTO::code).doesNotContain("COOLING");

        assertThatThrownBy(() -> service.update(id, new ChargeTypeDTO(id, "CHILLER", "District Cooling", null,
                AccountRole.COOLING_CHARGES, ChargeBehaviour.FEE, true, true, 55)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cannot be changed");

        // seeding after an edit does not resurrect the original row
        service.seedDefaults();
        assertThat(service.list(false)).hasSize(7);
    }
}
