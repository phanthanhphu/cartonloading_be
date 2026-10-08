package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.ShippingSchedule;

import java.util.List;

public record ShippingScheduleDetail(
        ShippingSchedule schedule,
        List<ShippingSchedulePoRow> pos,
        int totalPos,
        int readyPos,
        int totalCartons,
        int ssccAssignedCartons,
        int passCartons,
        int failedCartons,
        int remainingWeightCartons,
        boolean readyToSendToWeight
) { }
