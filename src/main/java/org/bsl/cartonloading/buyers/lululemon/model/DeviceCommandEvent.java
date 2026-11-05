package org.bsl.cartonloading.buyers.lululemon.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.bsl.cartonloading.common.constants.AppConstants;
import java.time.Instant;

/** Trace requests and their communication result. Does NOT indicate motor feedback. */
@Data
@Document(collection = AppConstants.Collection.DEVICE_COMMAND_EVENTS)
public class DeviceCommandEvent {
    @Id private String id;
    private String buyerCode = "LULULEMON";
    private String factoryCode;
    private String stationCode;
    private String deviceCode;
    private String requestId;
    private String action;
    private String outcome; // ACKNOWLEDGED | FAILED
    private String errorMessage;
    private String requestedBy;
    private Instant requestedAt;
    private Instant completedAt;
}
