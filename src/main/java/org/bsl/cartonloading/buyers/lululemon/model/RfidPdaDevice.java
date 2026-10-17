package org.bsl.cartonloading.buyers.lululemon.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.List;

/** Global PDA registry: Reader can be shared across many Factories; physical capture remains exclusive. */
@Data
@Document(collection = "lululemon_rfid_pda_devices")
public class RfidPdaDevice {
    @Id private String serial;
    private String stationCode; // legacy scale code; preserved for historical records
    private String factoryCode; // legacy one-factory assignment; converted lazily on update
    private boolean defaultReader; // legacy default flag
    private List<String> factoryCodes; // multi-Factory registration
    private List<String> defaultFactoryCodes; // where this PDA is the default reader
    private String label;
    private Integer startX;
    private Integer startY;
    private Integer stopX;
    private Integer stopY;
}
