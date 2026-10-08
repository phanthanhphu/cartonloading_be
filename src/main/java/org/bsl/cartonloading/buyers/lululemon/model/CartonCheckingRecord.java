package org.bsl.cartonloading.buyers.lululemon.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.bsl.cartonloading.common.constants.AppConstants;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** Append-only QC inspection result, independent of packing/RFID/weighing states. */
@Data
@NoArgsConstructor
@Document(collection = AppConstants.Collection.LULULEMON_CARTON_CHECKING)
public class CartonCheckingRecord {
    @Id private String id;
    private String buyerCode;
    private String orderId;
    private String poId;
    private String poNumber;
    private String cartonId;
    private Integer cartonNo;
    private String sscc18;
    private String sku;
    private String result;
    private String note;
    private String checkedBy;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime checkedAt;
}
