package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.ShippingSchedule;

public record ShippingScheduleSummary(
        ShippingSchedule schedule,
        int totalPos,
        int readyPos,
        int totalCartons,
        int ssccAssignedCartons,
        int passCartons,
        int failedCartons,
        int remainingWeightCartons,
        boolean readyToSendToWeight
) { }
