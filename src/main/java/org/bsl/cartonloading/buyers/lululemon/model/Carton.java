package org.bsl.cartonloading.buyers.lululemon.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.bsl.cartonloading.common.constants.AppConstants;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Document(collection = AppConstants.Collection.CARTONS)
public class Carton {
    @Id
    private String id;
    private String buyerCode;
    @org.springframework.data.mongodb.core.mapping.Field("buyer_key")
    private String buyerKey = org.bsl.cartonloading.common.constants.AppConstants.Buyer.LULULEMON;
    /** Parent Packing Order for the order-scoped LULULEMON workflow. */
    private String orderId;
    private String poId;
    private String poNumber;
    private String factoryCode;
    private Integer cartonNo;
    private Integer plannedQty;
    private Integer scannedQty;
    private String status;

    /** Unique official carton identity assigned only after the physical Carton Loading label is verified. */
    private String sscc18;

    /** Latest weighing result. Full history is kept in the shared weighing_results collection. */
    private BigDecimal expectedWeightKg;
    private BigDecimal actualWeightKg;
    private BigDecimal weightDifferenceKg;
    private BigDecimal weightToleranceKg;
    private String weightStatus;
    /** RFID is an independent inspection: never overwrite barcode scannedQty or weightStatus. */
    private String rfidStatus;
    private Integer rfidVerifiedQty;
    private String rfidSessionId;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime rfidVerifiedAt;
    private String weightStationCode;
    private String weighedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime weighedAt;

    private String packedBy;
    private String startedBy;
    private String finishedBy;
    private String updatedBy;
    private String assignedBy;
    private String labelConfirmedBy;
    private String labelConfirmedSku;
    private String qtyConfirmedBy;
    private String ssccAssignedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime startedAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime finishedAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime labelConfirmedAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime qtyConfirmedAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime ssccAssignedAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
    /**
     * Derived, read-only carton-level final inspection status. Never mark FINAL_PASS
     * from weight alone; the result is derived from BOTH persisted checks.
     * This works for historical cartons without introducing a data migration.
     */
    public String getFinalCheckStatus() {
        if (AppConstants.WeightStatus.FAILED.equals(weightStatus) || "HOLD".equals(weightStatus)) {
            return "WEIGHT_FAILED";
        }
        if (!AppConstants.WeightStatus.PASS.equals(weightStatus)) {
            return "WAITING_WEIGHT";
        }
        if ("PASS".equals(rfidStatus)) {
            return "FINAL_PASS";
        }
        if ("FAILED".equals(rfidStatus)) {
            return "RFID_HOLD";
        }
        return "WAITING_RFID";
    }

    // Compatibility alias: the original model persists the official SSCC as
    // sscc18, while the order-scoped workflow uses the shorter Java property name.
    public String getSscc() { return sscc18; }
    public void setSscc(String value) { this.sscc18 = value; }

}
