package org.bsl.cartonloading.buyers.lululemon.agent;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

/** One scoped, revocable Local Scale Agent identity per Buyer/Factory. Token is stored ONLY as SHA-256 digest. */
@Data
@Document(collection = "device_agent_registrations")
public class ScaleAgentRegistration {
    @Id private String id;
    private String buyerKey;
    private String factoryCode;
    private String agentId;
    private String secretHash;
    private Instant issuedAt;
    private Instant lastSeen;
}
