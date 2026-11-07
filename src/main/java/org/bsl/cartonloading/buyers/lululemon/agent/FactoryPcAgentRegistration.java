package org.bsl.cartonloading.buyers.lululemon.agent;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

/** An independently enrolled Windows PC within one Buyer/Factory; never stores a plaintext token. */
@Data
@Document(collection = "device_pc_agent_registrations")
public class FactoryPcAgentRegistration {
    @Id private String id;
    private String buyerKey;
    private String factoryCode;
    private String agentId;
    private String displayName;
    private String secretHash;
    private Instant issuedAt;
    private Instant lastSeen;
    private boolean revoked;
}
