package org.bsl.cartonloading.buyers.lululemon.dto;

public record PrintRequestStatusRequest(
        String status,
        String note
) { }
