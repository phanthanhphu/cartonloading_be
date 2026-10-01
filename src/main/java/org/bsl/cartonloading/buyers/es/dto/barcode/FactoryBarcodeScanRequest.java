package org.bsl.cartonloading.buyers.es.dto.barcode;

public record FactoryBarcodeScanRequest(
        String stationCode,
        String factoryBarcode,
        String palletCode,
        String scanId,
        Boolean manualMode
) { }
