package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.LululemonPo;

public record LululemonPoSummary(
        LululemonPo po,
        int finishedCartons,
        int assignedCartons,
        int totalScannedQty
) { }
