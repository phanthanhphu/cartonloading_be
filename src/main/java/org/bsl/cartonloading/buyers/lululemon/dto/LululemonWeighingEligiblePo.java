package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.LululemonPo;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonWeightProfile;

public record LululemonWeighingEligiblePo(
        LululemonPo po,
        LululemonWeightProfile profile,
        int cartonCount
) { }
