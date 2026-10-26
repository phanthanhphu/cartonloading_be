package org.bsl.cartonloading.dto.admin;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.constants.AppMessages;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DepartmentRequest(
        @NotBlank @Pattern(regexp = AppConstants.Validation.FACTORY_CODE_REGEX, message = "Factory Code must be F1, F2, ... Fn") String factory,
        @NotBlank @Size(max = 120) String division,
        @NotBlank @Size(max = 160) String departmentName
) {}
