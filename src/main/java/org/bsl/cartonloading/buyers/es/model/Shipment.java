package org.bsl.cartonloading.buyers.es.model;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.bsl.cartonloading.common.constants.AppConstants;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Document(collection = AppConstants.Collection.SHIPMENTS)
public class Shipment {
    @Id private String id;
    private String buyerCode;
    @org.springframework.data.mongodb.core.mapping.Field("buyer_key")
    private String buyerKey;
    private String orderId;
    private String shipmentNo;
    private String destination;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate plannedDate;
    private String carrier;
    private String reference;
    private List<String> cartonIds;
    /** PREPARING, PLANNED, DISPATCHING, SHIPPED, CANCELLED. */
    private String status;
    /** Locks the plan against cancellation once Packing starts a weight job. */
    private boolean executionStarted;
    private String createdBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
    private String shippedBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime shippedAt;
}
