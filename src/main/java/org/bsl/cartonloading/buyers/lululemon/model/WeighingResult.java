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
@Document(collection = AppConstants.Collection.WEIGHING_RESULTS)
public class WeighingResult {
    @Id
    private String id;
    private String buyerCode;
    @org.springframework.data.mongodb.core.mapping.Field("buyer_key")
    private String buyerKey = org.bsl.cartonloading.common.constants.AppConstants.Buyer.LULULEMON;
    private String orderId;
    private String weighingOrderId;
    /** Retained for shipment history and schedule-level traceability. */
    private String shippingScheduleId;
    private String poId;
    private String poNumber;
    private String cartonId;
    private Integer cartonNo;
    private Integer cartonQty;
    private String sscc18;
    private String action;
    private BigDecimal expectedWeightKg;
    private BigDecimal actualWeightKg;
    private BigDecimal differenceKg;
    private BigDecimal toleranceKg;
    private String result;
    private String stationCode;
    private String source;
    private Boolean stable;
    private String reason;
    private String userId;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
}
