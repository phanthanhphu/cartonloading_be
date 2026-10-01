package org.bsl.cartonloading.buyers.lululemon.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Editable source-level fields for an imported LULULEMON PO.
 *
 * allBpRows is the preferred edit contract: it contains the source ALL_BP rows
 * with exact Excel header captions. The backend ignores/recalculates formula
 * columns (Q/R/S/X/Y/Z/AA) and accepts edits only for non-formula columns.
 *
 * The legacy PO-level fields remain for backward compatibility with older UI
 * builds. Operational fields such as SKU/status are intentionally excluded.
 */
public record LululemonPoUpdateRequest(
        String poNumber,
        String masterPo,
        String factoryCode,
        String styleNumber,
        String description,
        String color,
        String size,
        Integer totalQty,
        Integer qtyPerCarton,
        String dcCode,
        String destination,
        String channel,
        String packingPlan,
        String salesOrderPts,
        String shipMode,
        String season,
        String fwd,
        String cartonBoxSize,
        Double netWeightKg,
        Double grossWeightKg,
        LocalDate exFtyDate,
        List<Map<String, String>> allBpRows
) {}
