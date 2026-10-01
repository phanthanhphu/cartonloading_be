package org.bsl.cartonloading.buyers.es.dto.barcode;

public record FactoryBarcodeGenerateRequest(
        Integer year,
        String factoryCode,
        Integer quantity
) { }
