package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.LululemonCarton;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonCartonItem;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonPo;

public record LululemonScanResponse(
        LululemonPo po,
        LululemonCarton carton,
        LululemonCartonItem item,
        boolean skuAssignedToPo,
        String message
) { }
