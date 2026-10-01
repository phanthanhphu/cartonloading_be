package org.bsl.cartonloading.buyers.es.dto.barcode;

public record FactoryBarcodeSequenceResponse(
        Integer year,
        String factoryCode,
        Long nextRunningNumber,
        String nextBarcode
) { }
