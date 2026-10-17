package org.bsl.cartonloading.buyers.lululemon.dto;

import java.time.LocalDate;
import java.util.List;

public record ShippingScheduleCreateRequest(
        String shippingName,
        String factoryCode,
        LocalDate exFtyDate,
        List<String> poIds
) { }
