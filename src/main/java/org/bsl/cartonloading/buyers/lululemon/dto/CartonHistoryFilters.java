package org.bsl.cartonloading.buyers.lululemon.dto;

import java.util.List;
/** Filter values are computed only from POs visible in the current Factory scope. */
public record CartonHistoryFilters(List<String> masterPos, List<String> poNumbers,
                                   List<String> packingPlans, List<String> salesOrderPts) { }
