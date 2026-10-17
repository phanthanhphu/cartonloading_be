package org.bsl.cartonloading.buyers.lululemon.model;

import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/** One claim per (Buyer, Order, PO number), enforced by MongoDB _id. */
@Data
@Document(collection = "lululemon_po_pick_claims")
public class PoPickClaim {
    @Id private String id;
    @Field("buyer_key") private String buyerKey = "LULULEMON";
    private String buyerCode = "LULULEMON";
    private String orderId;
    private String poNumber;
    private String factoryCode;
    private String originalFactoryCode;
    private String pickedBy;
    private LocalDateTime pickedAt;
    private String state = "ACTIVE";
}
