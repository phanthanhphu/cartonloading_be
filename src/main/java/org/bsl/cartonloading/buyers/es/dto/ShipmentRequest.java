package org.bsl.cartonloading.buyers.es.dto;

import java.time.LocalDate;
import java.util.List;

public record ShipmentRequest(String orderId, String shipmentNo, String destination,
        LocalDate plannedDate, String carrier, String reference, List<String> cartonIds) { }
