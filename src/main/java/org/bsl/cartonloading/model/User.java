package org.bsl.cartonloading.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.factory.FactoryCodes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Document(collection = AppConstants.Collection.USERS)
public class User {


    @Id
    private String id;
    private String username;
    private String email;
    private String password;
    private String address;
    private String phone;
    private String role = AppConstants.Role.USER;
    private LocalDateTime createdAt;
    private String profileImageUrl;
    private boolean isEnabled;
    private long tokenVersion;
    private String departmentId;

    /** Buyer workspaces assigned to this account. ADMIN always receives all buyers. */
    private List<String> buyerPermissions = new ArrayList<>();
    /** Null on legacy users only; explicit [] denies all Type access. */
    private List<BuyerTypePermission> buyerTypePermissions;
    public List<BuyerTypePermission> getBuyerTypePermissions() {
        if (buyerTypePermissions == null) {
            return getBuyerPermissions().stream()
                    .filter(b -> !SystemType.legacyBuyerTypes(b).isEmpty())
                    .map(b -> new BuyerTypePermission(b, SystemType.legacyBuyerTypes(b))).toList();
        }
        return List.copyOf(buyerTypePermissions);
    }
    public void setBuyerTypePermissions(List<BuyerTypePermission> grants) {
        if (grants == null) { buyerTypePermissions = null; return; }
        java.util.Map<String, java.util.LinkedHashSet<String>> merged = new java.util.LinkedHashMap<>();
        for (BuyerTypePermission grant : grants) {
            if (grant == null) continue;
            String buyer = BuyerAccess.normalize(grant.buyerCode());
            if (buyer.isEmpty()) throw new IllegalArgumentException("Invalid Buyer in Type permissions");
            if (grant.types() != null && grant.types().stream().anyMatch(v -> SystemType.normalize(v).isEmpty()))
                throw new IllegalArgumentException("Invalid Type permission for " + buyer);
            merged.computeIfAbsent(buyer, ignored -> new java.util.LinkedHashSet<>()).addAll(SystemType.normalizeAll(grant.types()));
        }
        buyerTypePermissions = merged.entrySet().stream()
                .map(e -> new BuyerTypePermission(e.getKey(), List.copyOf(e.getValue())))
                .toList();
    }
    public boolean canAccessBuyerType(String buyer, String type) {
        String code = BuyerAccess.normalize(buyer);
        String normalizedType = SystemType.normalize(type);
        return !code.isEmpty() && !normalizedType.isEmpty() && canAccessBuyer(code)
                && (isAdminRole() || getBuyerTypePermissions().stream()
                       .anyMatch(grant -> code.equals(BuyerAccess.normalize(grant.buyerCode()))
                               && grant.types().contains(normalizedType)));
    }


    /**
     * Factory scope used by Buyer workflows such as LULULEMON.
     * Values are normalized codes such as F1, F8, F25. Only ADMIN bypasses the assigned Factory scope.
     */
    private List<String> factoryPermissions = new ArrayList<>();

    /** Null = legacy access, empty = no weight factories, otherwise explicit weigh-only scope. */
    private List<String> weighingFactoryPermissions;

    /**
     * User module access. ADMIN is always treated as full application access.
     * VIEW_SYSTEM is read-only and intentionally exclusive.
     */
    private List<String> accessPermissions = new ArrayList<>();

    public static String normalizeRole(String value) {
        if (value == null || value.trim().isEmpty()) {
            return AppConstants.Role.USER;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("ROLE_ADMIN".equals(normalized) || AppConstants.Role.ADMIN.equals(normalized) || "ADMINISTRATOR".equals(normalized)) {
            return AppConstants.Role.ADMIN;
        }

        // Legacy LEADER accounts are intentionally migrated at read/write time to USER.
        return AppConstants.Role.USER;
    }

    public static List<String> normalizeAccessPermissions(Collection<String> values, boolean admin) {
        if (admin) {
            return List.of(AppConstants.Permission.SALES, AppConstants.Permission.ASSIGN_BARCODE, AppConstants.Permission.PICK_PO, AppConstants.Permission.ASSIGN_WEIGHT, AppConstants.Permission.WEIGHT_CHECK, AppConstants.Permission.BARCODE_OPERATOR, AppConstants.Permission.PRINT_ROOM);
        }

        Set<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null) continue;
                String permission = value.trim().toUpperCase(Locale.ROOT);
                if (AppConstants.Permission.SALES.equals(permission) || AppConstants.Permission.VIEW_SYSTEM.equals(permission)
                        || AppConstants.Permission.ASSIGN_BARCODE.equals(permission) || AppConstants.Permission.PICK_PO.equals(permission) || AppConstants.Permission.ASSIGN_WEIGHT.equals(permission) || AppConstants.Permission.WEIGHT_CHECK.equals(permission)
                        || AppConstants.Permission.BARCODE_OPERATOR.equals(permission) || AppConstants.Permission.PRINT_ROOM.equals(permission)) {
                    normalized.add(permission);
                }
            }
        }

        // Read-only access is never combined with a write permission.
        if (normalized.contains(AppConstants.Permission.VIEW_SYSTEM)) {
            return List.of(AppConstants.Permission.VIEW_SYSTEM);
        }

        // Existing users without an access list safely become view-only.
        if (normalized.isEmpty()) {
            return List.of(AppConstants.Permission.VIEW_SYSTEM);
        }

        return new ArrayList<>(normalized);
    }

    public boolean isAdminRole() {
        return AppConstants.Role.ADMIN.equals(normalizeRole(role));
    }


    public boolean canManageSales() {
        return isAdminRole() || getAccessPermissions().contains(AppConstants.Permission.SALES);
    }

    /** Existing Packing accounts keep PO Picking; PICK_PO can be granted independently. */
    public boolean canPickPo() {
        return isAdminRole() || canAssignBarcode() || getAccessPermissions().contains(AppConstants.Permission.PICK_PO);
    }

    public boolean canAssignBarcode() {
        return isAdminRole() || getAccessPermissions().contains(AppConstants.Permission.ASSIGN_BARCODE);
    }

    public boolean canManageBarcodes() {
        return canManageSales() || getAccessPermissions().contains(AppConstants.Permission.BARCODE_OPERATOR);
    }

    public boolean canAssignWeight() {
        return isAdminRole() || getAccessPermissions().contains(AppConstants.Permission.ASSIGN_WEIGHT);
    }

    public boolean canWeightCheck() {
        return isAdminRole() || getAccessPermissions().contains(AppConstants.Permission.WEIGHT_CHECK);
    }

    public boolean canPrintRoom() {
        return isAdminRole() || getAccessPermissions().contains(AppConstants.Permission.PRINT_ROOM);
    }

    public boolean isViewOnly() {
        return !isAdminRole()
                && getAccessPermissions().size() == 1
                && getAccessPermissions().contains(AppConstants.Permission.VIEW_SYSTEM);
    }

    public List<String> getBuyerPermissions() {
        return BuyerAccess.normalizeAll(buyerPermissions, false);
    }

    public void setBuyerPermissions(List<String> buyerPermissions) {
        this.buyerPermissions = new ArrayList<>(BuyerAccess.normalizeAll(buyerPermissions, false));
    }

    public boolean canAccessBuyer(String buyerCode) {
        String normalized = BuyerAccess.normalize(buyerCode);
        return !normalized.isEmpty() && (isAdminRole() || getBuyerPermissions().contains(normalized));
    }

    public List<String> getFactoryPermissions() {
        Set<String> values = new LinkedHashSet<>();
        if (factoryPermissions != null) {
            for (String value : factoryPermissions) {
                String normalized = normalizeFactoryCode(value);
                if (!normalized.isEmpty()) values.add(normalized);
            }
        }
        return new ArrayList<>(values);
    }

    public void setFactoryPermissions(Collection<String> values) {
        this.factoryPermissions = new ArrayList<>();
        if (values == null) return;
        for (String value : values) {
            String normalized = normalizeFactoryCode(value);
            if (!normalized.isEmpty() && !this.factoryPermissions.contains(normalized)) this.factoryPermissions.add(normalized);
        }
    }

    public List<String> getWeighingFactoryPermissions() {
        return weighingFactoryPermissions == null ? getFactoryPermissions() : List.copyOf(weighingFactoryPermissions);
    }

    public void setWeighingFactoryPermissions(Collection<String> values) {
        this.weighingFactoryPermissions = new ArrayList<>();
        if (values == null) return;
        for (String value : values) {
            String code = normalizeFactoryCode(value);
            if (!FactoryCodes.isValid(code)) throw new IllegalArgumentException("Invalid Weighing Factory code: " + code);
            if (!weighingFactoryPermissions.contains(code)) weighingFactoryPermissions.add(code);
        }
    }

    public boolean canAccessWeighingFactory(String factoryCode) {
        if (isAdminRole()) return true;
        // Preserve existing Packing/read-only visibility; actual weighing mutations
        // still require WEIGHT_CHECK at the controller.
        if (!canWeightCheck()) return canAccessFactory(factoryCode);
        String code = normalizeFactoryCode(factoryCode);
        return !code.isEmpty() && getWeighingFactoryPermissions().contains(code);
    }

    public boolean canAccessFactory(String factoryCode) {
        if (isAdminRole()) return true;
        String normalized = normalizeFactoryCode(factoryCode);
        if (normalized.isEmpty()) return false;
        List<String> allowed = getFactoryPermissions();
        return allowed.contains(normalized);
    }

    public static String normalizeFactoryCode(String value) {
        if (value == null) return "";
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_*.-]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.length() > 40 ? normalized.substring(0, 40) : normalized;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getRole() { return normalizeRole(role); }
    public void setRole(String role) { this.role = normalizeRole(role); }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getProfileImageUrl() { return profileImageUrl; }
    public void setProfileImageUrl(String profileImageUrl) { this.profileImageUrl = profileImageUrl; }

    public boolean isEnabled() { return isEnabled; }
    public void setEnabled(boolean enabled) { isEnabled = enabled; }

    public long getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(long tokenVersion) { this.tokenVersion = tokenVersion; }

    public String getDepartmentId() { return departmentId; }
    public void setDepartmentId(String departmentId) { this.departmentId = departmentId; }

    public List<String> getAccessPermissions() {
        return normalizeAccessPermissions(accessPermissions, isAdminRole());
    }

    public void setAccessPermissions(List<String> accessPermissions) {
        this.accessPermissions = new ArrayList<>(normalizeAccessPermissions(accessPermissions, isAdminRole()));
    }
}
