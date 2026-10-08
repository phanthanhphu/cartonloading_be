package org.bsl.cartonloading.buyers.lululemon.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;

public record ShippingUpdateRequest(
        String poNumber,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate exFtyDate
) { }
