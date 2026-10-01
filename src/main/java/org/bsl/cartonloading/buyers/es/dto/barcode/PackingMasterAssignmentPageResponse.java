package org.bsl.cartonloading.buyers.es.dto.barcode;

import java.util.List;

/** Paginated Master-row response for Packing · Assign Barcode for Carton. */
public record PackingMasterAssignmentPageResponse(
        List<PackingMasterAssignmentRowResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        long totalMasterRows,
        long totalExpectedCartons,
        long totalGeneratedCartons,
        long totalAssignedCartons
) { }
