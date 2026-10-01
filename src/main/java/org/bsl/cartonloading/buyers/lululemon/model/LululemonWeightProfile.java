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
@Document(collection = "lululemon_weight_profiles")
public class LululemonWeightProfile {
    @Id
    private String id;
    private String buyerCode = "LULULEMON";
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
