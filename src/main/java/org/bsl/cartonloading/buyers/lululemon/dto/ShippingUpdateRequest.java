package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.common.constants.AppConstants;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;

public record ShippingUpdateRequest(
        String poNumber,
        @JsonFormat(pattern = AppConstants.DatePattern.DATE) LocalDate exFtyDate
) { }
