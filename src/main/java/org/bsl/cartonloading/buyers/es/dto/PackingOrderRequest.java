package org.bsl.cartonloading.buyers.es.dto;

import org.bsl.cartonloading.common.constants.AppMessages;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record PackingOrderRequest(
        @NotNull(message = AppMessages.START_ORDER_REQUIRED)
        LocalDate orderDate,

        @NotNull(message = AppMessages.END_ORDER_REQUIRED)
        LocalDate endOrderDate,

        @NotBlank(message = AppMessages.ORDER_NAME_REQUIRED)
        @Size(max = 200, message = AppMessages.ORDER_NAME_MAX_200)
        String orderName,

        @Size(max = 200, message = AppMessages.SUPPLIER_NAME_MAX_200)
        String supplierName,

        @Size(max = 80, message = AppMessages.SUPPLIER_NUMBER_MAX_80)
        String supplierNumber,

        @Size(max = 120, message = AppMessages.PRODUCTION_FACILITY_MAX_120)
        String productionFacility
) {
}
