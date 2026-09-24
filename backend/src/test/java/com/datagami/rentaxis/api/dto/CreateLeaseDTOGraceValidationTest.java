package com.datagami.rentaxis.api.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A lease's grace is 0..90 days, the range of the screens' spinner (review
 * P3-4). The draft endpoints take this body with {@code @Valid}, so a value
 * outside it is a 400 naming the field and the range, not a stored 999.
 */
class CreateLeaseDTOGraceValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 91, 999, 100000})
    void aGraceOutsideZeroToNinetyIsRejectedWithTheRange(int days) {
        Set<ConstraintViolation<CreateLeaseDTO>> violations = graceViolations(days);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("grace period must be between 0 and 90 days");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5, 90})
    void aGraceInRangeIsAccepted(int days) {
        assertThat(graceViolations(days)).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void noGraceIsAcceptedAndMeansInheritThePropertysDefault() {
        CreateLeaseDTO dto = new CreateLeaseDTO();
        dto.setGracePeriodDays(null);
        assertThat(validator.validateProperty(dto, "gracePeriodDays")).isEmpty();
    }

    private static Set<ConstraintViolation<CreateLeaseDTO>> graceViolations(int days) {
        CreateLeaseDTO dto = new CreateLeaseDTO();
        dto.setGracePeriodDays(days);
        return validator.validateProperty(dto, "gracePeriodDays");
    }
}
