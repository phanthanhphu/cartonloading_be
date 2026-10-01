package org.bsl.cartonloading.buyers.es.dto.carton;

import java.math.BigDecimal;

public record CartonManualCompleteRequest(
        String stationCode,
        String barcode,
        String palletCode,
        BigDecimal weightKg,
        String reason
) {
}
