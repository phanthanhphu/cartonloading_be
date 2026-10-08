package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.importing.TextNormalizer;

import java.util.*;

/** Live, read-only scan counters. Never estimate scan counts from planned Excel values. */
public record CartonScanProgress(
        int scannedQty,
        int scannedCartons,
        int scannedFullCartons,
        int scannedRemainderQty,
        int scannedRemainderCartons,
        int identifiedQty,
        int identifiedCartons,
        int identifiedFullCartons,
        int identifiedRemainderQty,
        int identifiedRemainderCartons
) {
    public static CartonScanProgress from(PurchaseOrder po, List<Carton> cartons) {
        Set<Integer> oddCartonNumbers = oddCartonNumbers(po);
        int scannedQty = 0;
        int scannedCartons = 0;
        int scannedFullCartons = 0;
        int scannedRemainderQty = 0;
        int scannedRemainderCartons = 0;
        int identifiedQty = 0;
        int identifiedCartons = 0;
        int identifiedFullCartons = 0;
        int identifiedRemainderQty = 0;
        int identifiedRemainderCartons = 0;
        for (Carton carton : cartons) {
            int planned = Math.max(0, Optional.ofNullable(carton.getPlannedQty()).orElse(0));
            int scanned = Math.max(0, Math.min(planned, Optional.ofNullable(carton.getScannedQty()).orElse(0)));
            scannedQty += scanned;
            boolean oddCarton = carton.getCartonNo() != null && oddCartonNumbers.contains(carton.getCartonNo());
            // Packing is a three-step carton identification workflow: assign SSCC,
            // confirm read-only planned Qty, then scan/apply the SKU. The final step
            // persists labelConfirmedAt, separate from PASS item verification/RFID.
            // Only a fully identified carton contributes its planned Qty to Orders.
            boolean identified = carton.getLabelConfirmedAt() != null
                    && carton.getSscc() != null && !carton.getSscc().isBlank()
                    && carton.getLabelConfirmedSku() != null && !carton.getLabelConfirmedSku().isBlank();
            if (identified && planned > 0) {
                identifiedQty += planned;
                identifiedCartons++;
                if (oddCarton) {
                    identifiedRemainderQty += planned;
                    identifiedRemainderCartons++;
                } else identifiedFullCartons++;
            }
            if (oddCarton) scannedRemainderQty += scanned;
            if (planned > 0 && scanned == planned) {
                scannedCartons++;
                if (oddCarton) scannedRemainderCartons++;
                else scannedFullCartons++;
            }
        }
        return new CartonScanProgress(scannedQty, scannedCartons, scannedFullCartons,
                scannedRemainderQty, scannedRemainderCartons,
                identifiedQty, identifiedCartons, identifiedFullCartons,
                identifiedRemainderQty, identifiedRemainderCartons);
    }

    /** Match odd Cartons by their exact position in the saved ALL_BP carton plan.
     * Merged PO rows can have different Pcs/ctn, so PO-level Pcs/ctn isn't enough. */
    private static Set<Integer> oddCartonNumbers(PurchaseOrder po) {
        Set<Integer> result = new HashSet<>();
        int cartonNo = 0;
        boolean validSource = po.getAllBpRows() != null && !po.getAllBpRows().isEmpty();
        if (validSource) {
            for (Map<String, String> sourceRow : po.getAllBpRows()) {
                int qty = allBpWholeNumber(sourceRow, AppConstants.AllBpKey.QTY);
                int pcs = allBpWholeNumber(sourceRow, AppConstants.AllBpKey.PCS_CTN);
                if (qty <= 0 || pcs <= 0) { validSource = false; break; }
                cartonNo += qty / pcs;
                if (qty % pcs != 0) result.add(++cartonNo);
            }
        }
        if (validSource && cartonNo == Optional.ofNullable(po.getCartonCount()).orElse(0)) return result;

        // Fallback for older PO documents without accurate ALL_BP source rows.
        result.clear();
        List<Integer> quantities = po.getCartonPlannedQuantities();
        int pcs = Optional.ofNullable(po.getQtyPerCarton()).orElse(0);
        if (quantities != null && pcs > 0) {
            for (int index = 0; index < quantities.size(); index++) {
                Integer quantity = quantities.get(index);
                if (quantity != null && quantity > 0 && quantity < pcs) result.add(index + 1);
            }
        } else if (pcs > 0 && Optional.ofNullable(po.getRemainderQty()).orElse(0) > 0
                && Optional.ofNullable(po.getCartonCount()).orElse(0) > 0) {
            result.add(po.getCartonCount());
        }
        return result;
    }

    private static int allBpWholeNumber(Map<String, String> sourceRow, String canonical) {
        if (sourceRow == null) return -1;
        Set<String> aliases = AppConstants.AllBp.ALIASES.getOrDefault(canonical, Set.of());
        for (Map.Entry<String, String> entry : sourceRow.entrySet()) {
            String header = TextNormalizer.headerKey(entry.getKey());
            if (!aliases.contains(header)) continue;
            try {
                return Integer.parseInt(Objects.toString(entry.getValue(), "").replace(",", "").trim());
            } catch (NumberFormatException ignored) { return -1; }
        }
        return -1;
    }
}
