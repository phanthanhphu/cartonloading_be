package org.bsl.cartonloading.buyers.lululemon.dto;

import java.time.LocalDateTime;
import java.util.List;

public record CheckingSearchResponse(
        List<PoRow> pos,
        List<CartonRow> cartons,
        boolean truncated
) {
    public record PoRow(String id, String orderId, String orderName, String poNumber, String style, String color, String size,
                        String sku, Integer plannedCartons) { }

    public record CartonRow(String id, String orderId, String orderName, String poId, String poNumber, Integer cartonNo,
                            String sscc18, String sku, String style, String color, String size,
                            Integer plannedQty, String packingStatus, boolean identified,
                            String lastCheck, String checkedBy, LocalDateTime checkedAt) { }
}
