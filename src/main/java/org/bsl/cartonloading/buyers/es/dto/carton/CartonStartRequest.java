package org.bsl.cartonloading.buyers.es.dto.carton;

public record CartonStartRequest(
        String stationCode,
        String barcode,
        String packingLineId,
        String palletCode
) {
}
