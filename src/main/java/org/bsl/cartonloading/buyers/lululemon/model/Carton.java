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
    private String buyerKey = AppConstants.Buyer.LULULEMON;
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
    @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME)
    private LocalDateTime rfidVerifiedAt;
    private String weightStationCode;
    private String weighedBy;

    @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME)
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

    @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME)
    private LocalDateTime startedAt;
    @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME)
    private LocalDateTime finishedAt;
    @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME)
    private LocalDateTime labelConfirmedAt;
    @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME)
    private LocalDateTime qtyConfirmedAt;
    @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME)
    private LocalDateTime ssccAssignedAt;
    @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME)
    private LocalDateTime createdAt;
    @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME)
    private LocalDateTime updatedAt;
    /**
     * Derived, read-only carton-level final inspection status. Never mark FINAL_PASS
     * from weight alone; the result is derived from BOTH persisted checks.
     * This works for historical cartons without introducing a data migration.
     */
    public String getFinalCheckStatus() {
        if (AppConstants.RfidCheck.FAILED.equals(rfidStatus)) return AppConstants.FinalCheck.RFID_HOLD;
        if (!AppConstants.RfidCheck.PASS.equals(rfidStatus)) return AppConstants.FinalCheck.WAITING_RFID;
        if (AppConstants.WeightStatus.FAILED.equals(weightStatus) || AppConstants.WeightStatus.HOLD.equals(weightStatus)) return AppConstants.FinalCheck.WEIGHT_FAILED;
        if (!AppConstants.WeightStatus.PASS.equals(weightStatus)) return AppConstants.FinalCheck.WAITING_WEIGHT;
        return AppConstants.FinalCheck.FINAL_PASS;
    }

    // Compatibility alias: the original model persists the official SSCC as
    // sscc18, while the order-scoped workflow uses the shorter Java property name.
    public String getSscc() { return sscc18; }
    public void setSscc(String value) { this.sscc18 = value; }

}
