package org.bsl.cartonloading.buyers.lululemon.dto;

import java.math.BigDecimal;

public record LululemonWeightSubmitRequest(
        String sscc,
        BigDecimal actualWeightKg,
        String stationCode,
        Boolean stable,
        String source
) { }
