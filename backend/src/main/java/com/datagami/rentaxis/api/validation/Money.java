package com.datagami.rentaxis.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An amount of money on a request: at most two decimals and no larger than
 * {@link MoneyAmounts#MAX}; with {@link #positive()}, at least 0.01; otherwise
 * not negative unless {@link #allowNegative()}. Null passes — pair with
 * {@code @NotNull} where the amount is required. See {@link MoneyAmounts}.
 */
@Documented
@Constraint(validatedBy = MoneyValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT,
        ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Money {

    /** Must be at least one fil (0.01). */
    boolean positive() default false;

    /** May be below zero (a signed adjustment). Ignored when {@link #positive()}. */
    boolean allowNegative() default false;

    String message() default "Invalid amount";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
