package org.bsl.cartonloading.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BuyerAdminRequest(
        @NotBlank @Size(max = 60) String buyerKey,
        @NotBlank @Size(max = 160) String buyerName,
        Boolean active,
        Integer sequence,
        @Size(max = 500) String description
) {}
