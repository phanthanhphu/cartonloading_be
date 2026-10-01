package org.bsl.cartonloading.buyers.es.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bsl.cartonloading.buyers.es.enums.CartonScanStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Document(collection = "carton_scan_transactions")
public class CartonScanTransaction {
    @Id
    private String id;

    private Long jobId;
    private String buyerCode;
    private String orderId;
    private String orderName;
    private String masterLineId;
    private String packingLineId;
    private Integer packingLineNo;
    private String stationCode;
    private String palletCode;
    /** Last physical code scanned to start this weight job (legacy QA code or Factory Barcode). */
    private String barcode;

    /** Unique Factory Use Only barcode assigned to this physical carton before warehouse/weight checking. */
    private String factoryBarcode;
    private String factoryBarcodeAssignedBy;
    private String productionLine;
    private BigDecimal assignedQuantity;
    private String shipmentId;
    private String shipmentNo;
    private String manualInspectionResult;
    private String inspectionNote;
    private String inspectedBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime inspectedAt;
    private String completedBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime completedAt;
    private String shippedBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime shippedAt;

    public String getLifecycleStatus() {
        return org.bsl.cartonloading.buyers.es.support.CartonLifecycle.state(
                factoryBarcode != null && !factoryBarcode.isBlank(),
                inspectedAt != null || weighedAt != null || weightKg != null,
                completedAt != null, shippedAt != null,
                status == CartonScanStatus.CANCELLED);
    }

    public boolean isInspectionPassed() {
        return org.bsl.cartonloading.buyers.es.support.CartonLifecycle.passed(weightStatus, manualInspectionResult);
    }
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime factoryBarcodeAssignedAt;

    /** Client-generated identifier used to make a Zebra scan request idempotent. */
    @Indexed(unique = true, sparse = true)
    private String scanId;

    private String supplierName;
    private String supplierNumber;
    private String poNumber;
    private String articleNumber;
    private String styleNumber;
    private String style;
    private String color;
    private String size;
    private BigDecimal qtyPerCarton;
    private BigDecimal cartonPcs;
    /** Physical carton sequence inside the matching Master Data item, e.g. 1/10. */
    private Integer cartonSequence;
    /** Total physical carton count for the matching Master Data item. */
    private Integer plannedCartons;
    /** Continuous generated carton sequence inside the selected Order. */
    private Integer orderCartonSequence;
    /** Carton number from the Packing List CTNO range when available. */
    private Integer cartonNumber;
    /** Human-readable unique key: e.s. PO + e.s. Article No + sequence. */
    private String itemKey;
    /** Physical-carton sequence inside Buyer + Order + PO + Article. */
    private Integer itemSequence;
    /** Total physical cartons inside Buyer + Order + PO + Article. */
    private Integer itemTotal;
    /** Internal software-only item identifier; it is not printed as an extra physical label. */
    private String cartonCode;

    private BigDecimal expectedWeightKg;
    private BigDecimal weightToleranceKg;
    private BigDecimal weightKg;
    private BigDecimal weightDifferenceKg;
    /** NOT_WEIGHED, NO_STANDARD, OK, UNDER or OVER. */
    private String weightStatus;
    private CartonScanStatus status;
    private String warningMessage;
    private String plcMessageId;
    private String manualReason;
    /** PLC or MANUAL. */
    private String weightSource;

    /** Admin correction metadata. Resetting weight never removes the assigned Factory Barcode. */
    private String lastCorrectionReason;
    private String lastCorrectedBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastCorrectedAt;

    private String scannedBy;
    private String weighedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime scannedAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime weighedAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
