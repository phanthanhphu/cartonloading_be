package org.bsl.cartonloading.model;

import lombok.Data;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;

/** Factory and Department remain distinct data; they share one Admin management screen. */
@Data
@Document(collection = AppConstants.Collection.FACTORIES)
@CompoundIndex(name = "factory_buyer_code_unique", def = "{'buyer_key':1,'code':1}", unique = true)
public class Factory {
    @Id private String id;
    @Field("buyer_key") private String buyerKey = AppConstants.Buyer.LULULEMON;
    private String code;
    private String name;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
