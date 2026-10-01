package org.bsl.cartonloading.security;

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


    public boolean canManageSales() {
        return currentUser().map(user -> user.isEnabled() && user.canManageSales()).orElse(false);
    }

    public boolean canAssignBarcode() {
        return currentUser().map(user -> user.isEnabled() && user.canAssignBarcode()).orElse(false);
    }

    public boolean canWeightCheck() {
        return currentUser().map(user -> user.isEnabled() && user.canWeightCheck()).orElse(false);
    }

    public boolean canPrintRoom() {
        return currentUser().map(user -> user.isEnabled() && user.canPrintRoom()).orElse(false);
    }

    public boolean canManageBarcodes() {
        return currentUser().map(user -> user.isEnabled() && user.canManageBarcodes()).orElse(false);
    }

    public boolean canAccessBuyer(String buyerCode) {
        return currentUser().map(user -> user.isEnabled() && user.canAccessBuyer(buyerCode)).orElse(false);
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
