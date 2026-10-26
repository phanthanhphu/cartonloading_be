package org.bsl.cartonloading.buyers.lululemon.model;

import org.bsl.cartonloading.common.constants.AppConstants;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Persist every attempt independently so a failed/rescanned carton remains traceable. */
@Data
@NoArgsConstructor
@Document(collection = AppConstants.Collection.RFID_SCAN_SESSIONS)
@CompoundIndex(name = "rfid_carton_history", def = "{'buyerCode':1,'cartonId':1,'startedAt':-1}")
public class RfidScanSession {
    @Id private String id;
    private String buyerCode;
    @org.springframework.data.mongodb.core.mapping.Field("buyer_key")
    private String buyerKey = org.bsl.cartonloading.common.constants.AppConstants.Buyer.LULULEMON;
    private String orderId;
    private String poId;
    private String poNumber;
    private String cartonId;
    private Integer cartonNo;
    private String sscc18;
    private String stationCode;
    private String expectedSku;
    private Integer expectedQty;
    private Integer actualQty = 0;
    private Integer readCount = 0;
    /** SCANNING, PASS, FAILED. */
    private String status;
    private String reason;
    private String startedBy;
    private String finishedBy;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private List<String> epcs = new ArrayList<>();
    /** Idempotency keys for successfully imported PDA batches (not each tag read). */
    private List<String> processedImportIds = new ArrayList<>();
    private List<String> invalidReads = new ArrayList<>();
    private List<String> wrongSkuEpcs = new ArrayList<>();
    private List<String> crossCartonEpcs = new ArrayList<>();
}
