package org.bsl.cartonloading.dto.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UserAdminRequest(
        @NotBlank @Size(max = 120) String username,
        @NotBlank @Email @Size(max = 200) String email,
        @Size(min = 8, max = 200) String password,
        @Size(max = 200) String address,
        @Size(max = 80) String phone,
        String role,
        Boolean enabled,
        String departmentId,
        List<String> accessPermissions,
        List<String> buyerPermissions,
        List<String> factoryPermissions
) {}
