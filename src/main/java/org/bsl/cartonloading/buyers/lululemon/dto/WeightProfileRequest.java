package org.bsl.cartonloading.buyers.lululemon.dto;

import java.math.BigDecimal;

public record WeightProfileRequest(
        BigDecimal unitWeightKg,
        BigDecimal tareWeightKg,
        BigDecimal toleranceKg
) { }
