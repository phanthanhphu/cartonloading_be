package org.bsl.cartonloading.buyers.lululemon.model;

import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Data
@Document(collection = "lululemon_po_pick_history")
public class PoPickEvent {
    @Id private String id;
    @Field("buyer_key") private String buyerKey = "LULULEMON";
    private String orderId;
    private String poNumber;
    private String factoryCode;
    private String actor;
    private String action;
    /** Owner whose assignment was released, when action = UNPICK. */
    private String previousPickedBy;
    /** Required when one Packing releases another Packing's PO. */
    private String reason;
    private LocalDateTime createdAt;
}
