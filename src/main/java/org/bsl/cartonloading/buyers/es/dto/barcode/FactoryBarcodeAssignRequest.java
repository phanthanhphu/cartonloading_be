package org.bsl.cartonloading.buyers.es.dto.barcode;

public record FactoryBarcodeAssignRequest(
        String factoryBarcode,
        String cartonId,
        java.math.BigDecimal actualQuantity,
        String productionLine
) {
    public FactoryBarcodeAssignRequest(String factoryBarcode, String cartonId) {
        this(factoryBarcode, cartonId, null, null);
    }
}
