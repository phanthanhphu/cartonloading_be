package org.bsl.cartonloading.buyers.lululemon.dto;

import java.util.List;

public record PrintRequestCreateRequest(
        List<String> poIds,
        String note
) { }
