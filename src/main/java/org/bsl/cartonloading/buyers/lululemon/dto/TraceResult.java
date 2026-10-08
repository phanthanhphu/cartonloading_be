package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.CartonItem;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.model.ScanEvent;
import org.bsl.cartonloading.buyers.lululemon.model.WeighingResult;

import java.util.List;

public record TraceResult(
        PurchaseOrder po,
        Carton carton,
        List<CartonItem> items,
        List<ScanEvent> events,
        List<WeighingResult> weightEvents
) { }
