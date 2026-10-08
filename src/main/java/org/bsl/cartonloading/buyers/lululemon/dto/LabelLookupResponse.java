package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;

import java.util.List;

/**
 * Response shared by both LULULEMON carton-label APIs.
 *
 * The legacy API consumes sku + candidates, while the order-scoped workflow
 * also returns the selected PO, the pending cartons and an instruction message.
 * Keeping both shapes here avoids breaking either API surface.
 */
public record LabelLookupResponse(
        String sku,
        List<LabelCandidate> candidates,
        PurchaseOrder po,
        List<Carton> cartons,
        String message
) {
    public LabelLookupResponse(String sku, List<LabelCandidate> candidates) {
        this(sku, candidates == null ? List.of() : List.copyOf(candidates), null, List.of(), null);
    }

    public LabelLookupResponse(PurchaseOrder po, List<Carton> cartons, String message) {
        this(
                po == null ? null : po.getSku(),
                toCandidates(po, cartons),
                po,
                cartons == null ? List.of() : List.copyOf(cartons),
                message
        );
    }

    private static List<LabelCandidate> toCandidates(PurchaseOrder po, List<Carton> cartons) {
        if (cartons == null || cartons.isEmpty()) return List.of();
        return cartons.stream()
                .map(carton -> new LabelCandidate(po, carton))
                .toList();
    }
}
