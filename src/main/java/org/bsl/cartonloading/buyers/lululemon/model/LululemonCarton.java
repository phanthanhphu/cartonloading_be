package org.bsl.cartonloading.buyers.lululemon.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Document(collection = "lululemon_cartons")
public class LululemonCarton {
    @Id
    private String id;
    private String buyerCode = "LULULEMON";
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

    /** Latest weighing result. Full history is kept in lululemon_weight_events. */
    private BigDecimal expectedWeightKg;
    private BigDecimal actualWeightKg;
    private BigDecimal weightDifferenceKg;
    private BigDecimal weightToleranceKg;
    private String weightStatus;
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
    private String ssccAssignedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime startedAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime finishedAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime labelConfirmedAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime ssccAssignedAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
    // Compatibility alias: the original model persists the official SSCC as
    // sscc18, while the order-scoped workflow uses the shorter Java property name.
    public String getSscc() { return sscc18; }
    public void setSscc(String value) { this.sscc18 = value; }

}
