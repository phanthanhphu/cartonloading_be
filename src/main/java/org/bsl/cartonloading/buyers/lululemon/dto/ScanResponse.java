package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.CartonItem;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;

public record ScanResponse(
        PurchaseOrder po,
        Carton carton,
        CartonItem item,
        boolean skuAssignedToPo,
        String message
) { }
