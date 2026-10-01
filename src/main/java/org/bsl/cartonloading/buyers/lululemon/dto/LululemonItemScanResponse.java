package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.LululemonCarton;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonCartonItem;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonPo;

public record LululemonItemScanResponse(
        LululemonPo po,
        LululemonCarton carton,
        LululemonCartonItem item,
        boolean skuAssignedNow,
        String message
) { }
