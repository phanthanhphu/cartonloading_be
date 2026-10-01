package org.bsl.cartonloading.buyers.es.dto.barcode;

import java.math.BigDecimal;

/**
 * Stage 2 row shown to Packing. One row represents one Master Data line, not one physical carton.
 * Physical cartons are opened only after the operator selects the Master row.
 */
public record PackingMasterAssignmentRowResponse(
        String masterLineId,
        Integer lineNo,
        String poNumber,
        String articleNumber,
        String styleNumber,
        String style,
        String color,
        String size,
        BigDecimal qtyPerCarton,
        BigDecimal totalPcs,
        int totalCartons,
        long generatedCartons,
        long packedCount,
        long assignedCount,
        long remainingCount,
        boolean cartonCountMatches,
        String assignmentStatus
) { }
