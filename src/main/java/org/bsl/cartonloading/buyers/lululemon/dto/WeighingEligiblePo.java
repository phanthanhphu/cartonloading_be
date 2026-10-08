package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.model.WeightProfile;

public record WeighingEligiblePo(
        PurchaseOrder po,
        WeightProfile profile,
        int cartonCount
) { }
