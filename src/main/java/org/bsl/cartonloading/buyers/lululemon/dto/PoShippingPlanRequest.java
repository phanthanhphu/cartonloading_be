package org.bsl.cartonloading.buyers.lululemon.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.util.List;

public record PoShippingPlanRequest(
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate shippingDate,
        List<String> poIds
) { }
