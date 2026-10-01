package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.LululemonCarton;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonPo;

import java.util.List;

/**
 * Response shared by both LULULEMON carton-label APIs.
 *
 * The legacy API consumes sku + candidates, while the order-scoped workflow
 * also returns the selected PO, the pending cartons and an instruction message.
 * Keeping both shapes here avoids breaking either API surface.
 */
public record LululemonLabelLookupResponse(
        String sku,
        List<LululemonLabelCandidate> candidates,
        LululemonPo po,
        List<LululemonCarton> cartons,
        String message
) {
    public LululemonLabelLookupResponse(String sku, List<LululemonLabelCandidate> candidates) {
        this(sku, candidates == null ? List.of() : List.copyOf(candidates), null, List.of(), null);
    }

    public LululemonLabelLookupResponse(LululemonPo po, List<LululemonCarton> cartons, String message) {
        this(
                po == null ? null : po.getSku(),
                toCandidates(po, cartons),
                po,
                cartons == null ? List.of() : List.copyOf(cartons),
                message
        );
    }

    private static List<LululemonLabelCandidate> toCandidates(LululemonPo po, List<LululemonCarton> cartons) {
        if (cartons == null || cartons.isEmpty()) return List.of();
        return cartons.stream()
                .map(carton -> new LululemonLabelCandidate(po, carton))
                .toList();
    }
}
