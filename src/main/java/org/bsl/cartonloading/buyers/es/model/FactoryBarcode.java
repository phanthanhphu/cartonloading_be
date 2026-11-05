package org.bsl.cartonloading.buyers.es.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bsl.cartonloading.buyers.es.enums.FactoryBarcodeStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.bsl.cartonloading.common.constants.AppConstants;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Document(collection = AppConstants.Collection.FACTORY_BARCODES)
public class FactoryBarcode {
    @Id
    private String id;

    /** Human-readable/scannable unique code: YY + factory code + 9-digit running number. */
    private String barcode;
    private Integer year;
    private String factoryCode;
    private Long runningNumber;
    private String batchId;
    private FactoryBarcodeStatus status = FactoryBarcodeStatus.AVAILABLE;

    private Integer printCount = 0;
    private String lastPrintedBy;
    @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME)
    private LocalDateTime lastPrintedAt;

    /** Assignment snapshot for quick traceability/search. */
    private String assignedBuyerCode;
    @org.springframework.data.mongodb.core.mapping.Field("buyer_key")
    private String buyerKey;
    private String assignedOrderId;
    private String assignedOrderName;
    private String assignedCartonId;
    private String assignedCartonCode;
    private Integer assignedCartonNumber;
    private String assignedMasterLineId;
    private String assignedPoNumber;
    private String assignedArticleNumber;
    private String assignedColor;
    private String assignedSize;
    /** Standard Qty/CTN from Master Data. */
    private String assignedQtyPerCarton;
    /** Actual quantity in this physical carton (important for partial last cartons). */
    private String assignedQuantity;
    private String assignedBy;
    @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME)
    private LocalDateTime assignedAt;

    private String voidReason;
    private String voidBy;
    @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME)
    private LocalDateTime voidAt;

    private String createdBy;
    @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME)
    private LocalDateTime createdAt;
    @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME)
    private LocalDateTime updatedAt;
}
