package org.bsl.cartonloading.dto;

import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.model.User;

import java.util.ArrayList;
import java.util.List;

/** Authentication/authorization payload required by the Carton Loading frontend. */
public class UserDTO {
    private String id;
    private String username;
    private String email;
    private String role;
    private String address;
    private String phone;
    private boolean enabled;
    private String departmentId;
    private List<String> accessPermissions = new ArrayList<>();
    private boolean canManageSales;
    private boolean canAssignBarcode;
    private boolean canWeightCheck;
    private boolean viewOnly;
    private List<String> buyerPermissions = new ArrayList<>();
    private List<String> factoryPermissions = new ArrayList<>();

    private String clean(String value) { return value == null ? "" : value.trim(); }

    public String getId() { return id; }
    public void setId(String id) { this.id = clean(id); }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = clean(username); }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = clean(email); }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = User.normalizeRole(role); }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = clean(address); }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = clean(phone); }
    public boolean isEnabled() { return enabled; }
    public boolean getEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDepartmentId() { return departmentId; }
    public void setDepartmentId(String departmentId) { this.departmentId = clean(departmentId); }

    public List<String> getAccessPermissions() { return List.copyOf(accessPermissions); }
    public void setAccessPermissions(List<String> values) {
        this.accessPermissions = new ArrayList<>(User.normalizeAccessPermissions(values, User.ROLE_ADMIN.equals(getRole())));
    }

    public boolean isCanManageSales() { return canManageSales; }
    public boolean getCanManageSales() { return canManageSales; }
    public void setCanManageSales(boolean value) { this.canManageSales = value; }
    public boolean isCanAssignBarcode() { return canAssignBarcode; }
    public boolean getCanAssignBarcode() { return canAssignBarcode; }
    public void setCanAssignBarcode(boolean value) { this.canAssignBarcode = value; }
    public boolean isCanWeightCheck() { return canWeightCheck; }
    public boolean getCanWeightCheck() { return canWeightCheck; }
    public void setCanWeightCheck(boolean value) { this.canWeightCheck = value; }
    public boolean isViewOnly() { return viewOnly; }
    public boolean getViewOnly() { return viewOnly; }
    public void setViewOnly(boolean value) { this.viewOnly = value; }

    public List<String> getBuyerPermissions() { return List.copyOf(buyerPermissions); }
    public void setBuyerPermissions(List<String> values) {
        this.buyerPermissions = new ArrayList<>(BuyerAccess.normalizeAll(values, User.ROLE_ADMIN.equals(getRole())));
    }

    public List<String> getFactoryPermissions() { return List.copyOf(factoryPermissions); }
    public void setFactoryPermissions(List<String> values) {
        this.factoryPermissions = new ArrayList<>();
        if (values == null) return;
        for (String value : values) {
            String normalized = User.normalizeFactoryCode(value);
            if (!normalized.isEmpty() && !this.factoryPermissions.contains(normalized)) this.factoryPermissions.add(normalized);
        }
    }
}
