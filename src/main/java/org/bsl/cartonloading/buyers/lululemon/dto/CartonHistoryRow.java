package org.bsl.cartonloading.buyers.lululemon.dto;

import java.time.LocalDateTime;
/** Read-only, lightweight History listing. Detailed EPC/weight events load on demand. */
public record CartonHistoryRow(
    String cartonId, Integer cartonNo, String poId, String cartonStatus, String orderName, String factoryCode,
    String masterPo, String poNumber, String packingPlan, String salesOrderPts,
    String sscc18, String sku, Integer plannedQty,
    String rfidStatus, String weightStatus, String status,
    String scannedBy, String weighedBy, LocalDateTime scanTime,
    LocalDateTime weightTime, LocalDateTime updatedAt
) { }
