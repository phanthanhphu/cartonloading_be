package org.bsl.cartonloading.model;

import org.bsl.cartonloading.common.constants.AppConstants;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Buyer-key normalization shared by authentication, authorization and admin screens. */
public final class BuyerAccess {

    private BuyerAccess() { }

    public static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replace('&', '_')
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (AppConstants.Buyer.ENGELBERT_STRAUSS_SHORT_ALIAS.equals(normalized)
                || AppConstants.Buyer.ENGELBERT_STRAUSS_COMPACT_ALIAS.equals(normalized)) {
            normalized = AppConstants.Buyer.ENGELBERT_STRAUSS;
        }
        if (normalized.length() > 60 || !normalized.matches("[A-Z0-9][A-Z0-9_]*")) return "";
        return normalized;
    }

    /**
     * Normalize arbitrary configured Buyer keys. The old implementation filtered
     * this list to two hard-coded Buyers, which prevented true multi-buyer use.
     */
    public static List<String> normalizeAll(Collection<String> values, boolean ignoredAdminFlag) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String normalized = normalize(value);
                if (!normalized.isBlank()) result.add(normalized);
            }
        }
        return new ArrayList<>(result);
    }

    /** Syntactic validation only. Existence/active state is validated against the buyers collection. */
    public static boolean isSupported(String value) {
        return !normalize(value).isBlank();
    }

    public static boolean hasBuiltInModule(String value) {
        return AppConstants.Buyer.BUILT_IN.contains(normalize(value));
    }
}
