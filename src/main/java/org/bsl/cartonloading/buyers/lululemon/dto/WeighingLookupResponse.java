package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.shared.orders.model.OrderDocument;
import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.model.ShippingSchedule;
import org.bsl.cartonloading.buyers.lululemon.model.WeightProfile;
import org.bsl.cartonloading.buyers.lululemon.model.WeighingOrder;

import java.math.BigDecimal;

public record WeighingLookupResponse(
        WeighingOrder weighingOrder,
        PurchaseOrder po,
        Carton carton,
        WeightProfile profile,
        BigDecimal expectedWeightKg,
        String message,
        OrderDocument order,
        ShippingSchedule shippingSchedule
) { }
