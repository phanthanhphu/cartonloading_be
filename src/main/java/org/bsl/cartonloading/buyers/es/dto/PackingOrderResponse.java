package org.bsl.cartonloading.buyers.es.dto;

import org.bsl.cartonloading.common.constants.AppConstants;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record PackingOrderResponse(
        String id,
        String buyerCode,
        @JsonFormat(pattern = AppConstants.DatePattern.DATE) LocalDate orderDate,
        @JsonFormat(pattern = AppConstants.DatePattern.DATE) LocalDate endOrderDate,
        String orderName,
        String supplierName,
        String supplierNumber,
        String productionFacility,
        long masterLineCount,
        long packingLineCount,
        String status,
        boolean completed,
        long plannedCartonCount,
        long completedCartonCount,
        String assignmentStatus,
        long assignedCartonCount,
        long unassignedCartonCount,
        String weightStatus,
        long waitingWeightCartonCount,
        long passWeightCartonCount,
        long failWeightCartonCount,
        long notWeighedCartonCount,
        String createdBy,
        String updatedBy,
        @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME) LocalDateTime createdAt,
        @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME) LocalDateTime updatedAt,
        String factoryCode
) {
}
