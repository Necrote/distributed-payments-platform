package com.vivekpatel.payments.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Currency;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates {@link IsoCurrencyCode}. The JDK's code set is loaded once; it never changes at runtime. */
public class IsoCurrencyCodeValidator implements ConstraintValidator<IsoCurrencyCode, String> {

    private static final Set<String> CODES =
            Currency.getAvailableCurrencies().stream()
                    .map(Currency::getCurrencyCode)
                    .collect(Collectors.toUnmodifiableSet());

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // The JDK's codes are all upper case, so "inr" is rejected here, as the old pattern did.
        return value == null || CODES.contains(value);
    }
}
