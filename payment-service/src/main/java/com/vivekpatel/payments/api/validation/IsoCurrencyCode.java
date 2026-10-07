package com.vivekpatel.payments.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An upper-case ISO-4217 code that {@link java.util.Currency} actually knows.
 *
 * <p>A {@code ^[A-Z]{3}$} pattern is not enough: {@code "XYZ"} matches it, then {@code Money}
 * rejects it with an {@code IllegalArgumentException} deep in the service call, and the client gets
 * a 500 for what is plainly their bad input. Checking here turns it into a 400 that names the field.
 *
 * <p>{@code null} is valid; pair with {@code @NotBlank} where the field is required.
 */
@Documented
@Constraint(validatedBy = IsoCurrencyCodeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface IsoCurrencyCode {

    String message() default "must be a 3-letter ISO-4217 code";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
