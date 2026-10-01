package org.bsl.cartonloading.buyers.lululemon.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Document(collection = "lululemon_scan_events")
public class LululemonScanEvent {
    @Id
    private String id;

    private String buyerCode;
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
