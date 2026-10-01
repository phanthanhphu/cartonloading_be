package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.LululemonCarton;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonWeightEvent;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonWeighingOrder;

public record LululemonWeightSubmitResponse(
        LululemonWeighingOrder weighingOrder,
        LululemonCarton carton,
        LululemonWeightEvent event,
        String message
) { }
