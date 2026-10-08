package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.WeighingResult;
import org.bsl.cartonloading.buyers.lululemon.model.WeighingOrder;

public record WeightSubmitResponse(
        WeighingOrder weighingOrder,
        Carton carton,
        WeighingResult event,
        String message
) { }
