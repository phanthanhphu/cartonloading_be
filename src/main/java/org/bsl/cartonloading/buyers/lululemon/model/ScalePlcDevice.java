package org.bsl.cartonloading.buyers.lululemon.model;

import lombok.Data;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.ArrayList;
import java.util.List;

/** LULULEMON scale connection settings, not weight results. No raw register writes. */
@Data
@Document(collection = AppConstants.Collection.DEVICE_CONFIGURATIONS)
public class ScalePlcDevice {
    @Id private String stationCode;
    private String buyerKey = AppConstants.Buyer.LULULEMON;
    private String factoryCode;
    private String name;
    /** SIMULATOR is an in-app test source, MODBUS_TCP is a physical/external source. */
    private String connectionType = AppConstants.DeviceConnection.MODBUS_TCP;
    private String host;
    /** USB/RS232 cable attached to the backend host, not the browser. */
    /** BACKEND_DIRECT preserves existing Windows/dev profiles; LOCAL_AGENT routes through the Factory PC. */
    private String serialConnectionLocation = "BACKEND_DIRECT";
    private String serialComPort = "COM8";
    /** Optional v2 PC identity. Null retains the v1 per-Factory Agent transport. */
    private String serialAgentId;
    private int serialBaudRate = 9600;
    private int serialDataBits = 7;
    private String serialParity = "EVEN";
    private int serialStopBits = 1;
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
    /** Optional stable flag holding register, 0-based. When absent Modbus is preview only. */
    private Integer stableRegisterAddress;
    /** Transient per-carton latch: an unstable (0) sample must be seen before a new stable (1) capture. */
    private boolean modbusUnstableSeen;
    private java.math.BigDecimal modbusWeightKg;
    private java.time.Instant modbusAt;
    private String modbusEventId;
    private java.time.Instant modbusConsumedAt;
    private String encoding = AppConstants.Plc.UINT16;
    private double kgPerUnit = AppConstants.Plc.DEFAULT_KG_PER_UNIT;
    private boolean enabled = true;
    private List<String> assignedUserIds = new ArrayList<>();
}
