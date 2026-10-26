package org.bsl.cartonloading.common.factory;

import org.bsl.cartonloading.common.constants.AppConstants;
import java.util.Locale;

/** Shared syntactic validation only; activation/assignment are validated against MongoDB. */
public final class FactoryCodes {
    private FactoryCodes() { }
    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }
    public static boolean isValid(String raw) {
        return normalize(raw).matches(AppConstants.Validation.FACTORY_CODE_REGEX);
    }
    public static String requireValid(String raw) {
        String code = normalize(raw);
        if (!isValid(code)) throw new IllegalArgumentException("Factory code must be F1, F2, ... Fn");
        return code;
    }
}
