package org.bsl.cartonloading.buyers.lululemon.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;

public record CartonWeightAssignmentRow(
        String orderId,
        String orderNo,
        LocalDate orderDate,
        String shippingScheduleId,
        String shippingList,
        String factoryCode,
        LocalDate shippingDate,
        String weighingOrderId,
        String weighingStatus,
        String poId,
        String poNumber,
        String sku,
        String style,
        String color,
        String size,
        String cartonId,
        Integer cartonNo,
        Integer cartonQty,
        String sscc18,
        String weightStatus,
        String rfidStatus,
        String stationCode,
        LocalDateTime weighedAt,
        BigDecimal expectedWeightKg,
        BigDecimal minAllowedWeightKg,
        BigDecimal maxAllowedWeightKg,
        BigDecimal actualWeightKg,
        Integer rfidScannedQty,
        Integer rfidExpectedQty,
        Integer rfidReadCount,
        String rfidReason,
        Integer rfidWrongSkuCount,
        Integer rfidInvalidReadCount,
        Integer rfidCrossCartonCount
) { }
