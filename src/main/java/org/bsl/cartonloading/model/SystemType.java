package org.bsl.cartonloading.model;

import org.bsl.cartonloading.common.constants.AppConstants;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared allowed system types. A type is a workflow, not a Buyer. */
public final class SystemType {
    public static final String RFID = "RFID";
    public static final String UNRFID = "UNRFID";
    public static final String FACTORY_USE_ONLY = "FACTORY_USE_ONLY";
    private static final Set<String> VALID = Set.of(RFID, UNRFID, FACTORY_USE_ONLY);
    private SystemType() { }
    public static String normalize(String value) {
        if (value == null) return "";
        String result = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        return VALID.contains(result) ? result : "";
    }
    public static List<String> normalizeAll(Collection<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return List.copyOf(result);
    }
    /** Backward compatibility for Buyers created before the Type field existed. */
    public static List<String> legacyBuyerTypes(String buyerCode) {
        String key = BuyerAccess.normalize(buyerCode);
        if (AppConstants.Buyer.LULULEMON.equals(key)) return List.of(RFID);
        if (AppConstants.Buyer.ENGELBERT_STRAUSS.equals(key)) return List.of(FACTORY_USE_ONLY);
        return List.of();
    }
    /** Existing source contains RFID and Factory Use Only modules; UnRFID is reserved. */
    public static boolean hasImplementedWorkflow(String buyerCode, String type) {
        String key = BuyerAccess.normalize(buyerCode);
        String normalized = normalize(type);
        return (AppConstants.Buyer.LULULEMON.equals(key) && RFID.equals(normalized))
                || (AppConstants.Buyer.ENGELBERT_STRAUSS.equals(key) && FACTORY_USE_ONLY.equals(normalized));
    }
}
