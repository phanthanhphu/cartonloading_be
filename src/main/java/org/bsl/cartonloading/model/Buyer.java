package org.bsl.cartonloading.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.bsl.cartonloading.common.constants.AppConstants;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@Document(collection = AppConstants.Collection.BUYERS)
public class Buyer {
    @Id
    private String id;
    private String buyerKey;
    private String buyerName;
    private String slug;
    private boolean active = true;
    private int sequence;
    private String description;
    /** Null means older Buyer record; explicit empty means no active workflows. */
    private List<String> supportedTypes;
    public List<String> getSupportedTypes() {
        return supportedTypes == null ? SystemType.legacyBuyerTypes(buyerKey) : SystemType.normalizeAll(supportedTypes);
    }
    public void setSupportedTypes(List<String> values) { this.supportedTypes = SystemType.normalizeAll(values); }
    public boolean supportsType(String value) { return getSupportedTypes().contains(SystemType.normalize(value)); }

    private String createdBy;
    private String updatedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
