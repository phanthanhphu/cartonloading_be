package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import java.util.List;

/** Independent RFID review status; does not overwrite completed weight results. */
public final class RfidReviewStatus {
    public static final String PASS = "PASS";
    public static final String PENDING = "PENDING";
    public static final String ISSUES = "ISSUES";

    private RfidReviewStatus() { }

    public static String fromCartons(List<Carton> cartons) {
        if (cartons == null || cartons.isEmpty()) return PENDING;
        if (cartons.stream().anyMatch(c -> "FAILED".equals(c.getRfidStatus()))) return ISSUES;
        if (cartons.stream().allMatch(c -> PASS.equals(c.getRfidStatus()))) return PASS;
        return PENDING;
    }
}
