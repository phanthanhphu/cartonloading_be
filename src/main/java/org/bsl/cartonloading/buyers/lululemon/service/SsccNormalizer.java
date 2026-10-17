package org.bsl.cartonloading.buyers.lululemon.service;

/**
 * Normalizes Lululemon carton SSCC scans to the 18-digit database identity.
 * GS1 scanners can return AI 00 + SSCC-18 as 20 digits. Symbology identifiers
 * (e.g. ]C1) and the human-readable (00) format are also supported.
 * Leading zeroes are significant; never convert SSCC to a numeric type.
 */
public final class SsccNormalizer {
    private SsccNormalizer() {}

    public static String normalize(String input) {
        if (input == null || input.isBlank()) return null;
        String value = input.trim();
        if (value.startsWith("]C1") || value.startsWith("]d2")) {
            value = value.substring(3).trim();
        }
        if (value.startsWith("(00)")) value = value.substring(4).trim();

        // Existing scanners may add spaces, delimiters or line-breaks.
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.matches("00[0-9]{18}")) digits = digits.substring(2);
        return digits.matches("[0-9]{18}") ? digits : null;
    }
}
