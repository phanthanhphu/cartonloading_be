package org.bsl.cartonloading.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DepartmentRequest(
        @NotBlank @Pattern(regexp = "(?i)F[1-7]", message = "Factory must be F1 to F7") String factory,
        @NotBlank @Size(max = 120) String division,
        @NotBlank @Size(max = 160) String departmentName
) {}
