package com.datagami.rentaxis.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.math.BigDecimal;

/** {@link Money}: reports {@link MoneyAmounts}'s own sentence rather than one generic message. */
public class MoneyValidator implements ConstraintValidator<Money, BigDecimal> {

    private boolean positive;
    private boolean allowNegative;

    @Override
    public void initialize(Money annotation) {
        this.positive = annotation.positive();
        this.allowNegative = annotation.allowNegative();
    }

    @Override
    public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
        String problem = MoneyAmounts.problem(value, positive, allowNegative);
        if (problem == null) return true;
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(problem).addConstraintViolation();
        return false;
    }
}
