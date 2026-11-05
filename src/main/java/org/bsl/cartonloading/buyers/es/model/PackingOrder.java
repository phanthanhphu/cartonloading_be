package org.bsl.cartonloading.buyers.es.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.bsl.cartonloading.common.constants.AppConstants;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Document(collection = AppConstants.Collection.ORDERS)
public class PackingOrder {
    @Id
    private String id;

    private String buyerCode;
    /** Same physical orders collection for all buyers; business identity is retained separately. */
    @org.springframework.data.mongodb.core.mapping.Field("buyer_key")
    private String buyerKey;

    @JsonFormat(pattern = AppConstants.DatePattern.DATE)
    private LocalDate orderDate;

    @JsonFormat(pattern = AppConstants.DatePattern.DATE)
    private LocalDate endOrderDate;

    private String orderName;
    /** Canonical Order identity for LULULEMON; missing on legacy/ES records. */
    private String orderNameKey;
    private String supplierName;
    private String supplierNumber;
    private String productionFacility;
    /** LULULEMON order ownership; not a free-text production facility. */
    private String factoryCode;
    /** Prevent imports while an Admin cascade-delete is running or requires a retry. */
    private String deletionState;

    private String createdBy;
    private String updatedBy;

    @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME)
    private LocalDateTime createdAt;

    @JsonFormat(pattern = AppConstants.DatePattern.DATE_TIME)
    private LocalDateTime updatedAt;
}
