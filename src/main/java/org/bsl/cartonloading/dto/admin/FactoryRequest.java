package org.bsl.cartonloading.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.bsl.cartonloading.common.constants.AppConstants;

public record FactoryRequest(
        @NotBlank @Pattern(regexp = AppConstants.Validation.FACTORY_CODE_REGEX) String code,
        @NotBlank @Size(max = 120) String name,
        Boolean active
) { }
