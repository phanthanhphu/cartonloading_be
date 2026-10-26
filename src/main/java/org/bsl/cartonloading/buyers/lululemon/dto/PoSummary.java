package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;

public record PoSummary(
        PurchaseOrder po,
        int finishedCartons,
        int assignedCartons,
        int totalScannedQty,
        int scannedCartons,
        int scannedFullCartons,
        int scannedRemainderQty,
        int scannedRemainderCartons,
        int identifiedQty,
        int identifiedCartons,
        int identifiedFullCartons,
        int identifiedRemainderQty,
        int identifiedRemainderCartons,
        int completedQty,
        int completedCartons,
        int completedFullCartons,
        int completedRemainderQty,
        int completedRemainderCartons
) { }
