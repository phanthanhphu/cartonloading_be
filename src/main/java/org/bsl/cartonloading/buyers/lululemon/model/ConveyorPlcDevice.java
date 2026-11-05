package org.bsl.cartonloading.buyers.lululemon.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.bsl.cartonloading.common.constants.AppConstants;
import java.util.ArrayList;
import java.util.List;

/** Isolated ModbusPal-only conveyor testing configuration. NEVER drive an actual motor with this module. */
@Data
@Document(collection = AppConstants.Collection.DEVICE_CONFIGURATIONS)
public class ConveyorPlcDevice {
    @Id private String deviceCode;
    private String buyerKey = "LULULEMON";
    private String factoryCode;
    private String stationCode;
    private String name;
    /** Mode/protocol allow future profiles without enabling real motor commands. */
    private String mode = AppConstants.DeviceConnection.SIMULATOR;
    private String protocol = AppConstants.DeviceConnection.MODBUS_TCP;
    private String commissioningStatus = "DRAFT";
    private String host;
    private int port = 1502;
    private int unitId = 1;
    /** Monotonic saved-configuration revision for rejecting stale Admin edits. */
    private long revision = 0L;
    /** All addresses are ZERO-based Modbus coil offsets. */
    private int commandCoil = 0;
    private int motorFeedbackCoil = 1;
    private int rfidSensorCoil = 2;
    private int weightSensorCoil = 3;
    private int faultCoil = 4;
    private boolean enabled = true;
    private List<String> assignedUserIds = new ArrayList<>();
}