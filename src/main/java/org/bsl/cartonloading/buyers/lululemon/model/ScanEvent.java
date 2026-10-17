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
@Document(collection = AppConstants.Collection.SCAN_EVENTS)
public class ScanEvent {
    @Id
    private String id;

    private String buyerCode;
    @org.springframework.data.mongodb.core.mapping.Field("buyer_key")
    private String buyerKey = org.bsl.cartonloading.common.constants.AppConstants.Buyer.LULULEMON;
    private String orderId;
    private String poId;
    private String cartonId;
    /** ITEM, CARTON_LABEL_SKU, SSCC */
    private String scanType;
    private String rawValue;
    /** PASS or REJECT */
    private String result;
    private String message;
    private String actor;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
}
