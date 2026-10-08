package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.WeighingOrder;

public record WeighingQueueRow(
        WeighingOrder weighingOrder,
        int totalCartons,
        int passCartons,
        int failedCartons,
        int remainingCartons
) { }
