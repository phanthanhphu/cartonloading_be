package org.bsl.cartonloading.buyers.lululemon.dto;

import java.math.BigDecimal;

public record LululemonWeightProfileRequest(
        BigDecimal unitWeightKg,
        BigDecimal tareWeightKg,
        BigDecimal toleranceKg
) { }
