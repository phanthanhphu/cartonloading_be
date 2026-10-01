package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.LululemonCarton;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonPo;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonWeightProfile;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonWeighingOrder;

import java.math.BigDecimal;

public record LululemonWeighingLookupResponse(
        LululemonWeighingOrder weighingOrder,
        LululemonPo po,
        LululemonCarton carton,
        LululemonWeightProfile profile,
        BigDecimal expectedWeightKg,
        String message
) { }
