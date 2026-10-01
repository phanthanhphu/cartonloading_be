package org.bsl.cartonloading.dto.management;
import java.math.BigDecimal;
public record CartonManagementRow(String id, String orderId, String buyerCode, String poKey, Integer cartonNo,
                                  String status, String cartonIdentity, Integer plannedQty, Integer scannedQty,
                                  BigDecimal weightKg, String weightStatus) {}
