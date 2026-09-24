package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.lease.ChargeTypeDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ChargeTypeServiceIT extends AbstractPostgresIT {

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

    /**
     * The creditable roles are an allow-list, not "liability or else income". A line
     * crediting BANK or RENT_RECEIVABLE would credit the very asset it is supposed to
     * debit — the entry still balances, so nothing downstream would have complained,
     * it would just quietly net the receivable to nothing. OUTPUT_VAT would book tax
     * nobody charged. All of them are refused before behaviour is even considered.
     */
    @Test
    void onlyDepositLiabilityAndIncomeRolesCanBeCredited() {
        assertThatThrownBy(() -> service.create(dto("BANK_FEE", AccountRole.BANK, ChargeBehaviour.FEE)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Role BANK cannot be credited by a charge type");
        assertThatThrownBy(() -> service.create(dto("VAT_RENT", AccountRole.OUTPUT_VAT, ChargeBehaviour.RENT)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Role OUTPUT_VAT cannot be credited by a charge type");
        // refused whatever the behaviour, including the one that would otherwise fit
        assertThatThrownBy(() -> service.create(dto("RR_DEPOSIT", AccountRole.RENT_RECEIVABLE, ChargeBehaviour.DEPOSIT)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Role RENT_RECEIVABLE cannot be credited by a charge type");
        // and an income role on the list still works
        assertThat(service.create(dto("SUNDRY", AccountRole.OTHER_INCOME, ChargeBehaviour.FEE)).role())
                .isEqualTo(AccountRole.OTHER_INCOME);
    }

    /** Update and seed go through the same guard, not just create. */
    @Test
    void theAllowListAlsoGuardsUpdate() {
        UUID id = service.getByCode("ADMIN_FEE").getId();
        assertThatThrownBy(() -> service.update(id, new ChargeTypeDTO(id, "ADMIN_FEE", "Admin Fee", null,
                AccountRole.PDC_RECEIVABLE, ChargeBehaviour.FEE, false, true, 30)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Role PDC_RECEIVABLE cannot be credited by a charge type");
        assertThat(service.getByCode("ADMIN_FEE").getRole()).isEqualTo(AccountRole.ADMIN_FEE);
    }

    /**
     * Pins the allow-list against the enum itself, so a role added to AccountRole
     * later has to be classified deliberately rather than inherited by a default.
     */
    @Test
    void everyRoleIsEitherCreditableOrExplicitlyNot() {
        var creditable = ChargeTypeService.CREDITABLE_ROLES.keySet();
        assertThat(creditable).containsExactlyInAnyOrder(
                AccountRole.ADVANCE_RENT, AccountRole.SECURITY_DEPOSIT, AccountRole.PARKING_DEPOSIT,
                AccountRole.RENTAL_INCOME, AccountRole.ADMIN_FEE, AccountRole.PARKING_INCOME,
                AccountRole.COOLING_CHARGES, AccountRole.MAINTENANCE_CHARGES, AccountRole.RENT_PENALTY,
                AccountRole.CHEQUE_RETURN_PENALTY, AccountRole.OTHER_INCOME, AccountRole.FORFEITED_INCOME);
        assertThat(Arrays.stream(AccountRole.values()).filter(r -> !creditable.contains(r)).toList())
                .containsExactlyInAnyOrder(
                        AccountRole.RENT_RECEIVABLE, AccountRole.PDC_RECEIVABLE, AccountRole.BANK,
                        AccountRole.CASH, AccountRole.OUTPUT_VAT, AccountRole.INPUT_VAT,
                        AccountRole.DISCOUNT_ALLOWED, AccountRole.ROUNDING_OFF,
                        AccountRole.OPENING_BALANCE_DIFFERENCE, AccountRole.OUTPUT_VAT_DEFERRED,
                        AccountRole.PDC_PAYABLE, AccountRole.BANK_CHARGES, AccountRole.BANK_INTEREST_INCOME,
                        AccountRole.BANK_SUSPENSE);
        // every seeded particular clears its own guard
        assertThat(service.list(false)).allSatisfy(t -> assertThat(creditable).contains(t.role()));
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
