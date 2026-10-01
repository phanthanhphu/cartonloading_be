package org.bsl.cartonloading.buyers.es.dto.carton;

public record CartonPlanScanRequest(
        String stationCode,
        String barcode,
        String palletCode
) {
}
