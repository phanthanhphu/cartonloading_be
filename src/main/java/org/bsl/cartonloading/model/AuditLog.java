package org.bsl.cartonloading.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import java.util.Map;
import org.springframework.data.mongodb.core.mapping.Document;
import org.bsl.cartonloading.common.constants.AppConstants;

import java.time.LocalDateTime;

@Document(collection = AppConstants.Collection.AUDIT_LOGS)
@CompoundIndex(name="audit_buyer_factory_time", def="{'buyerCode':1,'factoryCode':1,'createdAt':-1}")
public class AuditLog {

    @Id
    private String id;

    private String username;

    private String action;

    private String resourceType;

    private String resourceId;

    private String description;

    // Null factoryCode means global/legacy log: ADMIN ONLY. Never infer scope from description.
    private String buyerCode;
    private String factoryCode;
    private String result = "SUCCESS";
    private Map<String, Object> before;
    private Map<String, Object> after;
    private Map<String, Map<String, Object>> changes;

    private String ipAddress;

    private String userAgent;

    @Indexed
    private LocalDateTime createdAt;

    public AuditLog() {
        this.createdAt = LocalDateTime.now();
    }

    public AuditLog(String username, String action, String resourceType, String resourceId, String description) {
        this.username = username;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.description = description;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getBuyerCode() { return buyerCode; }
    public void setBuyerCode(String value) { buyerCode = value; }
    public String getFactoryCode() { return factoryCode; }
    public void setFactoryCode(String value) { factoryCode = value; }
    public String getResult() { return result; }
    public void setResult(String value) { result = value; }
    public Map<String, Object> getBefore() { return before; }
    public void setBefore(Map<String, Object> value) { before = value; }
    public Map<String, Object> getAfter() { return after; }
    public void setAfter(Map<String, Object> value) { after = value; }
    public Map<String, Map<String, Object>> getChanges() { return changes; }
    public void setChanges(Map<String, Map<String, Object>> value) { changes = value; }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}