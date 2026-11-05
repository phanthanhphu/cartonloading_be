package org.bsl.cartonloading.buyers.lululemon.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.bsl.cartonloading.common.constants.AppConstants;
import java.time.Instant;
import java.util.Map;

/** Latest observed signals only; never interpret command output as motor state. */
@Data
@Document(collection = AppConstants.Collection.DEVICE_RUNTIME_SNAPSHOTS)
public class DeviceRuntimeSnapshot {
    @Id private String deviceCode;
    private String buyerCode = "LULULEMON";
    private String factoryCode;
    private String stationCode;
    private String status; // CONNECTED, DISCONNECTED, STALE
    private Map<String, Object> signals;
    private Instant observedAt;
    private String errorMessage;
}
