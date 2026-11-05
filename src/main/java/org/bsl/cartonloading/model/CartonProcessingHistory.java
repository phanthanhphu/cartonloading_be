package org.bsl.cartonloading.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;

/** Shared, buyer-neutral read model. One current completed outcome per buyer/carton.
 * Full RFID/EPC and weight attempts remain in their existing event collections.
 */
@Data
@NoArgsConstructor
@Document(collection = AppConstants.Collection.CARTON_PROCESSING_HISTORY)
public class CartonProcessingHistory {
    @Id private String id;
    private String buyerCode;
    @org.springframework.data.mongodb.core.mapping.Field("buyer_key")
    private String buyerKey;
    private String orderId;
    private String orderName;
    private String poId;
    private String cartonId;
    private Integer cartonNo;
    private String cartonStatus;
    private String factoryCode;
    private String masterPo;
    private String poNumber;
    private String packingPlan;
    private String salesOrderPts;
    private String sscc18;
    private String sku;
    private Integer plannedQty;
    private String rfidStatus;
    private String weightStatus;
    private String status;
    private String scannedBy;
    private String weighedBy;
    private LocalDateTime scanTime;
    private LocalDateTime weightTime;
    private LocalDateTime updatedAt;
    private String rfidSessionId;
    private String weightEventId;

    public static String key(String buyerCode, String cartonId) {
        if (buyerCode == null || buyerCode.isBlank() || cartonId == null || cartonId.isBlank())
            throw new IllegalArgumentException("Buyer and Carton ID are required for History");
        return buyerCode + ":" + cartonId;
    }
}
