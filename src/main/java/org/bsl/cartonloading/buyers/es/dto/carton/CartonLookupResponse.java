package org.bsl.cartonloading.buyers.es.dto.carton;

import java.util.List;

public record CartonLookupResponse(
        boolean matched,
        String barcode,
        String normalizedBarcode,
        String message,
        List<CartonCandidateResponse> candidates,
        CartonProgressResponse progress
) {
}
