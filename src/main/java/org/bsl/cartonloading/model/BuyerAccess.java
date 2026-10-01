package org.bsl.cartonloading.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Buyer key normalization for the Carton Loading application. */
public final class BuyerAccess {
    public static final String LULULEMON = "LULULEMON";
    public static final String ENGELBERT_STRAUSS = "ENGELBERT_STRAUSS";
    public static final List<String> ALL = List.of(LULULEMON, ENGELBERT_STRAUSS);

    private BuyerAccess() { }

    public static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replace('&', '_')
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if ("ES".equals(normalized) || "ENGELBERTSTRAUSS".equals(normalized)) {
            normalized = ENGELBERT_STRAUSS;
        }
        if (normalized.length() > 60 || !normalized.matches("[A-Z0-9][A-Z0-9_]*")) return "";
        return normalized;
    }

    public static List<String> normalizeAll(Collection<String> values, boolean ignoredAdminFlag) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String normalized = normalize(value);
                if (ALL.contains(normalized)) result.add(normalized);
            }
        }
        return new ArrayList<>(result);
    }

    public static boolean isSupported(String value) {
        return ALL.contains(normalize(value));
    }
}
