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
@Document(collection = "lululemon_weight_events")
public class LululemonWeightEvent {
    @Id
    private String id;
    private String buyerCode = "LULULEMON";
    private String orderId;
    private String weighingOrderId;
    private String poId;
    private String poNumber;
    private String cartonId;
    private Integer cartonNo;
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
