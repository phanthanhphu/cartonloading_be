package org.bsl.cartonloading.buyers.lululemon.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.bsl.cartonloading.common.constants.AppConstants;
import java.time.Instant;

/** Packing-station binding, separate from the existing Scale/Conveyor/RFID registries. */
@Data
@Document(collection = AppConstants.Collection.DEVICE_CONFIGURATIONS)
public class StationDeviceBinding {
    @Id private String id; // LULULEMON|F3|PACK-01|CONVEYOR
    private String buyerCode = "LULULEMON";
    private String factoryCode;
    private String stationCode;
    private String deviceRole; // CONVEYOR or SCALE; RFID simulator is unchanged
    private String deviceCode;
    private boolean active = true;
    private Instant updatedAt;
    private String updatedBy;
}
