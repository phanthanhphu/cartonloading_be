package org.bsl.cartonloading.common.persistence;

import lombok.Data;
import org.bsl.cartonloading.buyers.lululemon.model.ScalePlcDevice;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Typed wrapper for Mongo atomic operations on the SCALE config in the shared Collection. */
@Data
@Document(collection = AppConstants.Collection.DEVICE_CONFIGURATIONS)
public class ScaleConfigurationRecord {
    @Id private String id; // namespaced original device ID
    private String buyerCode;
    private String deviceType;
    private String sourceCollection;
    private String originalId;
    private String factoryCode;
    private boolean deleted;
    private ScalePlcDevice config;
}
