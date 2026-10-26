package org.bsl.cartonloading.dto.management;
import java.math.BigDecimal;
/** Buyer-neutral carton listing. RFID/weight extras are populated only for LULULEMON. */
public record CartonManagementRow(String id, String orderId, String buyerCode, String poKey, Integer cartonNo,
                                  String status, String cartonIdentity, Integer plannedQty, Integer scannedQty,
                                  BigDecimal weightKg, String weightStatus,
                                  String rfidStatus, Integer rfidVerifiedQty,
                                  BigDecimal expectedWeightKg, BigDecimal weightToleranceKg,
                                  Integer rfidMatchedQty, Integer rfidWrongSkuQty, String labelConfirmedSku) {
    /** Existing 17-field signature; binary users must recompile when upgrading the DTO. */
    public CartonManagementRow(String id, String orderId, String buyerCode, String poKey, Integer cartonNo,
                               String status, String cartonIdentity, Integer plannedQty, Integer scannedQty,
                               BigDecimal weightKg, String weightStatus,
                               String rfidStatus, Integer rfidVerifiedQty,
                               BigDecimal expectedWeightKg, BigDecimal weightToleranceKg,
                               Integer rfidMatchedQty, Integer rfidWrongSkuQty) {
        this(id, orderId, buyerCode, poKey, cartonNo, status, cartonIdentity, plannedQty, scannedQty,
             weightKg, weightStatus, rfidStatus, rfidVerifiedQty, expectedWeightKg, weightToleranceKg,
             rfidMatchedQty, rfidWrongSkuQty, null);
    }

    /** Legacy 15-field constructor; keep existing consumers and ES DTO callers compatible. */
    public CartonManagementRow(String id, String orderId, String buyerCode, String poKey, Integer cartonNo,
                               String status, String cartonIdentity, Integer plannedQty, Integer scannedQty,
                               BigDecimal weightKg, String weightStatus,
                               String rfidStatus, Integer rfidVerifiedQty,
                               BigDecimal expectedWeightKg, BigDecimal weightToleranceKg) {
        this(id, orderId, buyerCode, poKey, cartonNo, status, cartonIdentity, plannedQty, scannedQty,
             weightKg, weightStatus, rfidStatus, rfidVerifiedQty, expectedWeightKg, weightToleranceKg, null, null);
    }

    /** Keep the existing constructor for other buyers / their carton listings. */
    public CartonManagementRow(String id, String orderId, String buyerCode, String poKey, Integer cartonNo,
                              String status, String cartonIdentity, Integer plannedQty, Integer scannedQty,
                              BigDecimal weightKg, String weightStatus) {
        this(id, orderId, buyerCode, poKey, cartonNo, status, cartonIdentity, plannedQty, scannedQty,
             weightKg, weightStatus, null, null, null, null);
    }
}
