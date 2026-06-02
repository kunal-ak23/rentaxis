package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.ChargeFrequency;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B4: bean-validation coverage for {@link LeaseChargeDTO} constraints and the
 * {@code @Valid} cascade from {@link CreateLeaseDTO#getCharges()}. Without the
 * cascade the nested charge constraints would silently never fire.
 */
class LeaseChargeValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private CreateLeaseDTO baseLease() {
        CreateLeaseDTO dto = new CreateLeaseDTO();
        dto.setUnitId(UUID.randomUUID());
        dto.setRenterId(UUID.randomUUID());
        dto.setStartDate(LocalDate.of(2026, 1, 1));
        dto.setEndDate(LocalDate.of(2026, 12, 31));
        dto.setRentAmount(new BigDecimal("30000"));
        dto.setDepositAmount(new BigDecimal("5000"));
        return dto;
    }

    private LeaseChargeDTO validCharge() {
        LeaseChargeDTO c = new LeaseChargeDTO();
        c.setName("Maintenance");
        c.setAmount(new BigDecimal("200"));
        c.setFrequency(ChargeFrequency.PER_INSTALLMENT);
        return c;
    }

    @Test
    void validLeaseWithValidCharge_hasNoViolations() {
        CreateLeaseDTO dto = baseLease();
        dto.setCharges(List.of(validCharge()));
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    void negativeChargeAmount_violatesViaCascade() {
        LeaseChargeDTO c = validCharge();
        c.setAmount(new BigDecimal("-1"));
        CreateLeaseDTO dto = baseLease();
        dto.setCharges(List.of(c));

        Set<ConstraintViolation<CreateLeaseDTO>> violations = validator.validate(dto);
        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).contains("charges[0].amount"));
    }

    @Test
    void blankChargeName_violatesViaCascade() {
        LeaseChargeDTO c = validCharge();
        c.setName("   ");
        CreateLeaseDTO dto = baseLease();
        dto.setCharges(List.of(c));

        Set<ConstraintViolation<CreateLeaseDTO>> violations = validator.validate(dto);
        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).contains("charges[0].name"));
    }

    @Test
    void chargeNameOver120Chars_violatesSizeConstraint() {
        LeaseChargeDTO c = validCharge();
        c.setName("x".repeat(121));
        CreateLeaseDTO dto = baseLease();
        dto.setCharges(List.of(c));

        Set<ConstraintViolation<CreateLeaseDTO>> violations = validator.validate(dto);
        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).contains("charges[0].name"));
    }

    @Test
    void nullChargeFrequency_violatesViaCascade() {
        LeaseChargeDTO c = validCharge();
        c.setFrequency(null);
        CreateLeaseDTO dto = baseLease();
        dto.setCharges(List.of(c));

        Set<ConstraintViolation<CreateLeaseDTO>> violations = validator.validate(dto);
        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).contains("charges[0].frequency"));
    }
}
