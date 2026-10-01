package org.bsl.cartonloading.buyers.es.dto.carton;

import java.math.BigDecimal;

public record PlcJobResponse(
        String stationCode,
        boolean scanReady,
        Long jobId,
        String transactionId,
        String articleNumber,
        Integer cartonSequence,
        Integer plannedCartons,
        BigDecimal minimumWeightKg
) {
}
