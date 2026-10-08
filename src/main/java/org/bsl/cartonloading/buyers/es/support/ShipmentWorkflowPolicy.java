package org.bsl.cartonloading.buyers.es.support;

import org.bsl.cartonloading.common.constants.AppMessages;
import java.math.BigDecimal;
import org.bsl.cartonloading.buyers.es.enums.ShipmentStatus;

/** Rules shared by assignment, shipment planning and weight execution. */
public final class ShipmentWorkflowPolicy {
    private ShipmentWorkflowPolicy() { }

    public static void requireQuantity(BigDecimal actual, BigDecimal planned) {
        if (planned == null || planned.signum() <= 0) {
            throw new IllegalArgumentException(AppMessages.PLANNED_QTY_MISSING);
        }
        if (actual == null || actual.signum() <= 0) {
            throw new IllegalArgumentException(AppMessages.ACTUAL_QTY_REQUIRED);
        }
        if (actual.compareTo(planned) != 0) {
            throw new IllegalArgumentException(
                    AppMessages.quantityMismatch(planned.stripTrailingZeros().toPlainString(), actual.stripTrailingZeros().toPlainString())
            );
        }
    }

    public static void requirePlanned(String status, boolean assigned, boolean member) {
        if (!ShipmentStatus.PLANNED.name().equals(status) || !assigned || !member) {
            throw new IllegalArgumentException(AppMessages.SCAN_ASSIGNED_CARTON_ACTIVE_SHIPMENT);
        }
    }

    public static void requireCancellable(String status, boolean executionStarted) {
        if (executionStarted || !(ShipmentStatus.PLANNED.name().equals(status) || ShipmentStatus.CANCELLED.name().equals(status))) {
            throw new IllegalArgumentException(AppMessages.SHIPMENT_CANCEL_LOCKED);
        }
    }
}
