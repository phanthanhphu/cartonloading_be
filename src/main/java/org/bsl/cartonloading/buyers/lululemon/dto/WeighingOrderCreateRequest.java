package org.bsl.cartonloading.buyers.lululemon.dto;

import java.util.List;

public record WeighingOrderCreateRequest(String name, List<String> poIds, String shippingScheduleId) { }
