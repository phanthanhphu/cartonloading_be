package org.bsl.cartonloading.common.importing;

import java.util.Locale;

public final class TextNormalizer {
    private TextNormalizer() {}

    public static String trimToNull(String value) {
        if (value == null) return null;
        String result = value.replace('\u00A0', ' ').trim().replaceAll("\\s+", " ");
        return result.isEmpty() ? null : result;
    }

    public static String key(String value) {
        String clean = trimToNull(value);
        return clean == null ? null : clean.toUpperCase(Locale.ROOT);
    }

    public static String headerKey(String value) {
        String clean = trimToNull(value);
        return clean == null ? "" : clean.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }
}
