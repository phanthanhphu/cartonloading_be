package org.bsl.cartonloading.buyers.lululemon.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.bsl.cartonloading.common.constants.AppConstants;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Document(collection = AppConstants.Collection.WEIGHING_ORDERS)
public class WeighingOrder {
    @Id
    private String id;
    private String buyerCode;
    private String orderId;
    private String name;
    private String factoryCode;
    /** Shipping / Ex-Factory schedule that released this work to Carton Weight. */
    private String shippingScheduleId;
    private List<String> poIds = new ArrayList<>();
    private List<String> cartonIds = new ArrayList<>();
    private String status;
    private String createdBy;
    private String updatedBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
