package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.common.constants.AppConstants;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.util.List;

public record PoShippingPlanRequest(
        @JsonFormat(pattern = AppConstants.DatePattern.DATE) LocalDate shippingDate,
        List<String> poIds
) { }
