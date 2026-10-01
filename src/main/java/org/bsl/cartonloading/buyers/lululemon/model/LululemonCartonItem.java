package org.bsl.cartonloading.buyers.lululemon.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Document(collection = "lululemon_carton_items")
public class LululemonCartonItem {
    @Id
    private String id;
    private String buyerCode = "LULULEMON";
    /** Parent Packing Order for the order-scoped LULULEMON workflow. */
    private String orderId;
    private String poId;
    private String poNumber;
    private String cartonId;
    private Integer cartonNo;
    private Integer itemNo;
    private String status;
    private String scannedSku;
    private String scannedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime scannedAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
