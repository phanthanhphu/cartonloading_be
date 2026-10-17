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
@Document(collection = AppConstants.Collection.WEIGHING_PROFILES)
public class WeightProfile {
    @Id
    private String id;
    private String buyerCode;
    @org.springframework.data.mongodb.core.mapping.Field("buyer_key")
    private String buyerKey = org.bsl.cartonloading.common.constants.AppConstants.Buyer.LULULEMON;
    private String orderId;
    private String poId;
    private String poNumber;
    private String sku;
    /** Standard net product weight for one physical item. */
    private BigDecimal unitWeightKg;
    /** Packaging/tare weight added once per carton. */
    private BigDecimal tareWeightKg;
    /** Absolute +/- tolerance in kilograms. */
    private BigDecimal toleranceKg;
    private String updatedBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
    private String createdBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
}
