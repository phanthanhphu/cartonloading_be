package org.bsl.cartonloading.buyers.es.dto.barcode;

import org.bsl.cartonloading.buyers.es.model.FactoryBarcode;

import java.util.List;

public record FactoryBarcodeGenerateResponse(
        String batchId,
        Integer year,
        String factoryCode,
        Long firstRunningNumber,
        Long lastRunningNumber,
        Integer quantity,
        List<FactoryBarcode> barcodes
) { }
