package org.bsl.cartonloading.dto.management;
import java.math.BigDecimal;
import java.time.LocalDateTime;
public record ItemManagementRow(String id, String cartonId, Integer itemNo, String sku, String style,
                                String color, String size, BigDecimal quantity, String status,
                                String scannedBy, LocalDateTime scannedAt) {}
