package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;

public record ShippingSchedulePoRow(
        PurchaseOrder po,
        int totalCartons,
        int finishedCartons,
        int ssccAssignedCartons,
        int passCartons,
        int failedCartons,
        int remainingWeightCartons,
        boolean skuAssigned,
        boolean readyForWeight
) { }
