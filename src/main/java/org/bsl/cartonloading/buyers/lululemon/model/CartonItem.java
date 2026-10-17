package org.bsl.cartonloading.buyers.lululemon.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.bsl.cartonloading.common.constants.AppConstants;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Document(collection = AppConstants.Collection.CARTON_ITEMS)
public class CartonItem {
    @Id
    private String id;
    private String buyerCode;
    @org.springframework.data.mongodb.core.mapping.Field("buyer_key")
    private String buyerKey = org.bsl.cartonloading.common.constants.AppConstants.Buyer.LULULEMON;
    /** Parent Packing Order for the order-scoped LULULEMON workflow. */
    private String orderId;
    private String poId;
    private String poNumber;
    private String cartonId;
    private Integer cartonNo;
    private Integer itemNo;
    /** Logical item lifecycle status (for example PENDING, IDENTIFIED, PASS). */
    private String status;
    /** RFID verification status is tracked independently from the logical item status. */
    private String rfidStatus;
    /** Device-level item identity, assigned to a logical slot only after carton RFID PASS. */
    private String rfidEpc;
    private String rfidSerialNumber;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime rfidVerifiedAt;
    /** SKU expected for this logical item slot, inherited from the confirmed carton label. */
    private String expectedSku;
    /** SKU captured by the legacy barcode fallback scan, if used. */
    private String scannedSku;
    private String scannedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime scannedAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
