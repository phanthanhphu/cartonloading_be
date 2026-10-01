package org.bsl.cartonloading.buyers.es.support;

import org.bsl.cartonloading.buyers.es.enums.CartonLifecycleStatus;
import org.bsl.cartonloading.buyers.es.enums.InspectionResult;
import org.bsl.cartonloading.buyers.es.enums.WeightStatus;

/** Business lifecycle is separate from the legacy scale/job status. */
public final class CartonLifecycle {
    private CartonLifecycle() { }

    public static String state(boolean assigned, boolean checked, boolean completed, boolean shipped, boolean cancelled) {
        if (shipped) return CartonLifecycleStatus.SHIPPED.name();
        if (cancelled) return CartonLifecycleStatus.CANCELLED.name();
        if (completed) return CartonLifecycleStatus.COMPLETED.name();
        if (checked) return CartonLifecycleStatus.CHECKED.name();
        return assigned ? CartonLifecycleStatus.ASSIGNED.name() : CartonLifecycleStatus.CREATED.name();
    }

    public static boolean passed(String weightStatus, String manualResult) {
        // A manual pass cannot conceal an outstanding measured weight failure.
        if (WeightStatus.UNDER.name().equals(weightStatus) || WeightStatus.OVER.name().equals(weightStatus) || InspectionResult.FAIL.name().equals(manualResult)) return false;
        return WeightStatus.OK.name().equals(weightStatus) || InspectionResult.PASS.name().equals(manualResult);
    }
}
