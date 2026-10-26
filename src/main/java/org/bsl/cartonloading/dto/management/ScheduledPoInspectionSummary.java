package org.bsl.cartonloading.dto.management;

/** Order-wide totals, never inferred from only the visible page of POs. */
public record ScheduledPoInspectionSummary(
        long totalScheduledPos,
        long completedPos,
        long incompletePos,
        long pendingRfidPos,
        long pendingWeightPos,
        long pendingBothPos,
        long rfidFailedPos,
        long weightFailedPos
) {}
