package org.bsl.cartonloading.buyers.lululemon.model;

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
@Document(collection = "lululemon_rfid_pda_captures")
public class RfidPdaCapture {
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
