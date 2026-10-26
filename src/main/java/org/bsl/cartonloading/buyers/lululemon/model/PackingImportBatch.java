package org.bsl.cartonloading.buyers.lululemon.model;

import lombok.Data;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** An immutable factory-scoped import receipt, not a copy of the uploaded binary. */
@Data
@Document(collection = "lululemon_packing_import_batches")
public class PackingImportBatch {
    @Id private String id;
    private String buyerCode = AppConstants.Buyer.LULULEMON;
    private String buyerKey = AppConstants.Buyer.LULULEMON;
    private String orderId;
    private String factoryCode;
    private String sourceFileName;
    private Integer rowCount;
    private String importedBy;
    private LocalDateTime importedAt;
}
