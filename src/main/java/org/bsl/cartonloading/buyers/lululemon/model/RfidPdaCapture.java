package org.bsl.cartonloading.buyers.lululemon.model;

import org.bsl.cartonloading.common.constants.AppConstants;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** Durable ADB capture reservation. No expiration is automatic: release requires user review. */
@Data
@NoArgsConstructor
@Document(collection = AppConstants.Collection.RFID_PDA_CAPTURES)
public class RfidPdaCapture {
    @org.springframework.data.mongodb.core.mapping.Field("buyer_key")
    private String buyerKey = org.bsl.cartonloading.common.constants.AppConstants.Buyer.LULULEMON;
    @Id private String serial;
    private String station;
    private String order;
    private String po;
    private String sscc;
    private String sessionId;
    private Set<String> baselineFiles = new LinkedHashSet<>();
    private boolean stopped;
    private Instant startedAt;
}
