package org.bsl.cartonloading.buyers.es.enums;

import java.util.List;

public enum CartonScanStatus {
    /** Carton child record generated from the WSP master and not scanned yet. */
    PLANNED,
    WAITING_WEIGHT,
    COMPLETED,
    WEIGHT_WARNING,
    CANCELLED;

    /** Statuses that reserve/count a carton once execution has started. */
    public static List<CartonScanStatus> countedStatuses() {
        return List.of(WAITING_WEIGHT, COMPLETED, WEIGHT_WARNING);
    }

    /** Final weighing states that count as completed execution. */
    public static boolean isCompleted(CartonScanStatus status) {
        return status == COMPLETED || status == WEIGHT_WARNING;
    }
}
