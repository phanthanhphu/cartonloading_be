package org.bsl.cartonloading.security;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component("accessControl")
public class AccessControl {
    private final UserRepository userRepository;

    public AccessControl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public boolean isAdmin() {
        return currentUser().map(user -> user.isEnabled() && user.isAdminRole()).orElse(false);
    }


    public boolean canViewAuditLogs() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getDetails() instanceof WorkspaceSession session)) return false;
        return currentUser().map(u -> u.isEnabled()
                && u.canAccessBuyerType(session.buyer(), session.type())
                && (u.isAdminRole() || (!u.getFactoryPermissions().isEmpty() && u.hasAnyAccessForBuyer(session.buyer())))).orElse(false);
    }

    private boolean lululemonSession() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getDetails() instanceof WorkspaceSession session
                && AppConstants.Buyer.LULULEMON.equals(session.buyer());
    }
    public boolean canLululemonRead() {
        return currentUser().map(user -> user.isEnabled() && user.canLululemonRead()).orElse(false);
    }
    public boolean canManageShipping() {
        return currentUser().map(user -> user.isEnabled() && (lululemonSession()
                ? user.canLululemonShippingScheduler() : user.canManageSales())).orElse(false);
    }
    /** Independent permission to create a new Shipping Date; factory and order remain scoped by the service. */
    public boolean canCreateShippingSchedule() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!lululemonSession() || auth == null || !(auth.getDetails() instanceof WorkspaceSession session))
            return false;
        return currentUser().map(user -> user.isEnabled()
                && user.canAccessBuyerType(session.buyer(), session.type())
                && user.canLululemonCreateShippingSchedule()).orElse(false);
    }
    /** Only Admin or a LULULEMON user explicitly granted Schedule Reopen may unlock a submitted date. */
    public boolean canReopenShippingSchedule() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!lululemonSession() || auth == null || !(auth.getDetails() instanceof WorkspaceSession session))
            return false;
        return currentUser().map(user -> user.isEnabled()
                && user.canAccessBuyerType(session.buyer(), session.type())
                && user.canLululemonReopenShippingSchedule()).orElse(false);
    }
    public boolean canReadBuyerData() {
        return currentUser().map(user -> user.isEnabled() && (lululemonSession()
                ? user.canLululemonRead() : (user.canManageSales() || user.canAssignBarcode()
                    || user.canWeightCheck() || user.canAssignWeight() || user.isViewOnly()))).orElse(false);
    }

    public boolean canManageSales() {
        return currentUser().map(user -> user.isEnabled() && (lululemonSession() ? user.canLululemonUploadEdit() : user.canManageSales())).orElse(false);
    }

    public boolean canAssignBarcode() {
        return currentUser().map(user -> user.isEnabled() && (lululemonSession() ? user.canLululemonUploadEdit() : user.canAssignBarcode())).orElse(false);
    }

    public boolean canPickPo() { return currentUser().map(user -> user.isEnabled() && (!lululemonSession() && user.canPickPo())).orElse(false); }

    public boolean canAssignWeight() { return currentUser().map(user -> user.isEnabled() && (!lululemonSession() && user.canAssignWeight())).orElse(false); }

    public boolean canWeightCheck() {
        return currentUser().map(user -> user.isEnabled() && (lululemonSession() ? user.canLululemonScanWeight() : user.canWeightCheck())).orElse(false);
    }

    public boolean canManageBarcodes() {
        return currentUser().map(user -> user.isEnabled() && (!lululemonSession() && user.canManageBarcodes())).orElse(false);
    }

    public boolean canAccessBuyer(String buyerCode) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getDetails() instanceof WorkspaceSession session)
                || !session.buyer().equals(BuyerAccess.normalize(buyerCode))) return false;
        return currentUser().map(user -> user.isEnabled() && user.canAccessBuyerType(session.buyer(), session.type())).orElse(false);
    }

    public boolean canAccessFactory(String factoryCode) {
        return currentUser().map(user -> user.isEnabled() && user.canAccessFactory(factoryCode)).orElse(false);
    }

    public boolean canAccessUser(String userId) {
        return currentUser().map(user -> user.isEnabled() && (user.isAdminRole() || user.getId().equals(userId))).orElse(false);
    }

    public boolean canChangeOwnPassword(String email) {
        return currentUser().map(user -> user.isEnabled() && user.getEmail() != null && user.getEmail().equalsIgnoreCase(email)).orElse(false);
    }

    private Optional<User> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return Optional.empty();
        }
        return userRepository.findByEmail(authentication.getName());
    }
}
