package org.bsl.cartonloading.buyers.lululemon.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Reporting-only RFID issue counters. Unsupported/invalid raw tags count as
 * Wrong SKU in operator feedback, once per unique tag (not once per read).
 * This never modifies the persisted EPCs, quantities, or PASS/FAILED rules.
 */
public final class RfidScanIssueCounts {
    private RfidScanIssueCounts() { }

    public static int uniqueUnsupportedCount(Collection<String> invalidReads) {
        return normalizedRawTags(invalidReads, true).size();
    }

    public static int wrongSkuIncludingUnsupported(Collection<String> wrongSkuEpcs, Collection<String> invalidReads) {
        Set<String> combined = normalizedRawTags(wrongSkuEpcs, false);
        combined.addAll(normalizedRawTags(invalidReads, true));
        return combined.size();
    }

    public static int withUnsupported(int decodedWrongCount, Collection<String> invalidReads) {
        return decodedWrongCount + uniqueUnsupportedCount(invalidReads);
    }

    private static Set<String> normalizedRawTags(Collection<String> entries, boolean stripReason) {
        Set<String> result = new LinkedHashSet<>();
        if (entries == null) return result;
        for (String value : entries) {
            if (value == null) continue;
            String text = value.trim();
            if (stripReason) {
                int index = text.indexOf(" : ");
                if (index >= 0) text = text.substring(0, index);
            }
            String raw = text.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            if (!raw.isEmpty()) result.add(raw);
        }
        return result;
    }
}
