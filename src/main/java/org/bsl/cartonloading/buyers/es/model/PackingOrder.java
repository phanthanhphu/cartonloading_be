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

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate orderDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endOrderDate;

    private String orderName;
    private String supplierName;
    private String supplierNumber;
    private String productionFacility;
    /** LULULEMON order ownership; not a free-text production facility. */
    private String factoryCode;

    private String createdBy;
    private String updatedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
