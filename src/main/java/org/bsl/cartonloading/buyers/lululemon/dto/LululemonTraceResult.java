package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.LululemonCarton;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonCartonItem;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonPo;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonScanEvent;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonWeightEvent;

import java.util.List;

public record LululemonTraceResult(
        LululemonPo po,
        LululemonCarton carton,
        List<LululemonCartonItem> items,
        List<LululemonScanEvent> events,
        List<LululemonWeightEvent> weightEvents
) { }
