package org.bsl.cartonloading.buyers.es.support;

import java.math.BigDecimal;
import org.bsl.cartonloading.buyers.es.enums.ShipmentStatus;

/** Rules shared by assignment, shipment planning and weight execution. */
public final class ShipmentWorkflowPolicy {
    private ShipmentWorkflowPolicy() { }

    public static void requireQuantity(BigDecimal actual, BigDecimal planned) {
        if (planned == null || planned.signum() <= 0) {
            throw new IllegalArgumentException("Planned quantity is missing for this physical carton. Ask Sales to correct the Master Data before packing.");
        }
        if (actual == null || actual.signum() <= 0) {
            throw new IllegalArgumentException("Actual quantity is required before the Factory Barcode can be assigned.");
        }
        if (actual.compareTo(planned) != 0) {
            throw new IllegalArgumentException(
                    "Quantity Mismatch. Planned: " + planned.stripTrailingZeros().toPlainString()
                            + " pcs, Actual: " + actual.stripTrailingZeros().toPlainString()
                            + " pcs. Correct the carton quantity before continuing."
            );
        }
    }

    public static void requirePlanned(String status, boolean assigned, boolean member) {
        if (!ShipmentStatus.PLANNED.name().equals(status) || !assigned || !member) {
            throw new IllegalArgumentException("Scan an assigned carton in an active shipment plan from Sales.");
        }
    }

    public static void requireCancellable(String status, boolean executionStarted) {
        if (executionStarted || !(ShipmentStatus.PLANNED.name().equals(status) || ShipmentStatus.CANCELLED.name().equals(status))) {
            throw new IllegalArgumentException("Only a shipment that has not started packing/weight execution can be cancelled.");
        }
    }
}
