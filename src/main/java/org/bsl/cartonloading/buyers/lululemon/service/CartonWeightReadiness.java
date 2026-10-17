package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.common.constants.AppConstants;

/**
 * Lululemon Packing identifies the carton (SSCC + planned Qty + SKU) but intentionally
 * defers physical item verification to the RFID/Carton Weight station. READY_TO_PACK
 * therefore is NOT an error once the identity steps have been confirmed.
 * Keep this shared predicate aligned across Shipping Schedule, Weighing and RFID.
 */
public final class CartonWeightReadiness {
    private CartonWeightReadiness() {}

    public static boolean isReady(PurchaseOrder po, Carton carton) {
        if (po == null || carton == null) return false;
        if (SsccNormalizer.normalize(carton.getSscc18()) == null) return false;
        if (carton.getPlannedQty() == null || carton.getPlannedQty() <= 0) return false;
        String poSku = TrueVueDecoder.skuKey(po.getSku());
        if (poSku == null) return false;
        String labelSku = TrueVueDecoder.skuKey(carton.getLabelConfirmedSku());
        if (labelSku != null && !poSku.equals(labelSku)) return false;

        // New identification flow: Qty confirmed, then label SKU confirmed. A carton
        // remains READY_TO_PACK until RFID verifies its physical contents.
        // An earlier Packing release can have a confirmed label without a
        // dedicated qtyConfirmedAt timestamp. The label-confirm endpoint had
        // already validated planned Qty before recording that identity.
        boolean identified = carton.getLabelConfirmedAt() != null
                && poSku.equals(labelSku);
        if (identified && (AppConstants.CartonStatus.READY_TO_PACK.equals(carton.getStatus())
                || AppConstants.CartonStatus.READY_TO_SHIP.equals(carton.getStatus())
                || AppConstants.CartonStatus.LABEL_CONFIRMED.equals(carton.getStatus()))) return true;

        // Backward compatibility for cartons packed and released under the older flow.
        return AppConstants.CartonStatus.READY_TO_SHIP.equals(carton.getStatus())
                && (carton.getLabelConfirmedAt() != null || carton.getFinishedAt() != null);
    }
}
