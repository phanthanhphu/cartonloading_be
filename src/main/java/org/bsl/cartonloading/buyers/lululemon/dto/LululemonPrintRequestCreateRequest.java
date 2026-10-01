package org.bsl.cartonloading.buyers.lululemon.dto;

import java.util.List;

public record LululemonPrintRequestCreateRequest(
        List<String> poIds,
        String note
) { }
