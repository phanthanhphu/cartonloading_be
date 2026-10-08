package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.WeighingResult;

import java.time.LocalDate;

public record CartonWeightHistoryRow(
        WeighingResult result,
        String orderNo,
        String shippingList,
        String factoryCode,
        LocalDate shippingDate
) { }
