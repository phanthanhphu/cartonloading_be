package org.bsl.cartonloading.buyers.es.dto.carton;

import java.math.BigDecimal;

public record ManualWeightRequest(BigDecimal weightKg, String reason) {
}
