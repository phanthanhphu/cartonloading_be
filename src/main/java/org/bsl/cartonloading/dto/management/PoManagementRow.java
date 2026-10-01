package org.bsl.cartonloading.dto.management;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record PoManagementRow(
        String key,
        String id,
        String orderId,
        String buyerCode,
        String poNumber,
        String styleNumber,
        String sku,
        String factoryCode,
        String status,
        long cartonCount,
        long itemCount,
        String masterPo,
        String dcCode,
        String destination,
        String channel,
        String packingPlan,
        String salesOrderPts,
        String description,
        String color,
        String size,
        Integer totalQty,
        Integer qtyPerCarton,
        Integer plannedCartons,
        Integer remainderQty,
        String shipMode,
        String season,
        String fwd,
        String cartonBoxSize,
        Double netWeightKg,
        Double grossWeightKg,
        LocalDate exFtyDate,
        List<String> allBpHeaders,
        List<Map<String, String>> allBpRows
) {
    public PoManagementRow(
            String key,
            String id,
            String orderId,
            String buyerCode,
            String poNumber,
            String styleNumber,
            String sku,
            String factoryCode,
            String status,
            long cartonCount,
            long itemCount
    ) {
        this(key, id, orderId, buyerCode, poNumber, styleNumber, sku, factoryCode, status,
                cartonCount, itemCount,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null);
    }
}
