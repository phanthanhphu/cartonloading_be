package org.bsl.cartonloading.buyers.lululemon.dto;

import java.math.BigDecimal;

public record CartonProcessingWeightRequest(String sscc, String stationCode, BigDecimal actualWeightKg) { }
