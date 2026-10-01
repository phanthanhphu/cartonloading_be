package org.bsl.cartonloading.buyers.es.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record PackingOrderRequest(
        @NotNull(message = "Start Order is required")
        LocalDate orderDate,

        @NotNull(message = "End Order is required")
        LocalDate endOrderDate,

        @NotBlank(message = "Order Name is required")
        @Size(max = 200, message = "Order Name must not exceed 200 characters")
        String orderName,

        @Size(max = 200, message = "Supplier Name must not exceed 200 characters")
        String supplierName,

        @Size(max = 80, message = "e.s. Supplier # must not exceed 80 characters")
        String supplierNumber,

        @Size(max = 120, message = "Production Facility must not exceed 120 characters")
        String productionFacility
) {
}
