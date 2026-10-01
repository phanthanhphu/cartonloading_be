package org.bsl.cartonloading.buyers.es.dto.carton;

import java.util.List;

public record CartonItemLookupResponse(
        boolean matched,
        String barcode,
        String normalizedBarcode,
        String message,
        List<CartonMasterItemResponse> items
) {
}
