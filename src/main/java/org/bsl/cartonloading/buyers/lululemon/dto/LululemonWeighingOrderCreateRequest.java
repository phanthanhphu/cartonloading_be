package org.bsl.cartonloading.buyers.lululemon.dto;

import java.util.List;

public record LululemonWeighingOrderCreateRequest(String name, List<String> poIds) { }
