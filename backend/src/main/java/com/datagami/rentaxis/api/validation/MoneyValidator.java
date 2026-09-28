package com.datagami.rentaxis.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.math.BigDecimal;

/** {@link Money}: reports {@link MoneyAmounts}'s own sentence rather than one generic message. */
public class MoneyValidator implements ConstraintValidator<Money, BigDecimal> {

    private boolean positive;
    private boolean allowNegative;
    private BigDecimal max;

    @Override
    public void initialize(Money annotation) {
        this.positive = annotation.positive();
        this.allowNegative = annotation.allowNegative();
        this.max = annotation.max().isBlank() ? MoneyAmounts.MAX : new BigDecimal(annotation.max());
    }

    @Override
    public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
        String problem = MoneyAmounts.problem(value, positive, allowNegative, max);
        if (problem == null) return true;
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(problem).addConstraintViolation();
        return false;
    }
}
