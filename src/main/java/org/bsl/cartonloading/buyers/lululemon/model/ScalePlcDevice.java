package org.bsl.cartonloading.buyers.lululemon.model;

import lombok.Data;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.ArrayList;
import java.util.List;

/** LULULEMON scale connection settings, not weight results. No raw register writes. */
@Data
@Document(collection = AppConstants.Collection.SCALE_PLC_DEVICES)
public class ScalePlcDevice {
    @Id private String stationCode;
    private String buyerKey = AppConstants.Buyer.LULULEMON;
    private String factoryCode;
    private String name;
    /** SIMULATOR is an in-app test source, MODBUS_TCP is a physical/external source. */
    private String connectionType = "MODBUS_TCP";
    private String host;
    private java.math.BigDecimal simulatedWeightKg;
    private java.time.Instant simulatedAt;
    private String simulatedEventId;
    /** An armed station belongs to one active RFID-PASS SSCC at a time. */
    private String activeCartonSscc;
    private java.time.Instant activeUntil;
    private String simulatedCartonSscc;
    private java.time.Instant simulatedConsumedAt;
    private int port = AppConstants.Plc.DEFAULT_PORT;
    private int unitId = AppConstants.Plc.DEFAULT_UNIT;
    private int registerAddress;
    private String encoding = AppConstants.Plc.UINT16;
    private double kgPerUnit = AppConstants.Plc.DEFAULT_KG_PER_UNIT;
    private boolean enabled = true;
    private List<String> assignedUserIds = new ArrayList<>();
}
