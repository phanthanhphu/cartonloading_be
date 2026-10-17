package org.bsl.cartonloading.buyers.core;

import org.bsl.cartonloading.common.constants.AppMessages;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BuyerFactoryAccessService {
    private final UserRepository userRepository;

    public BuyerFactoryAccessService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new IllegalArgumentException(AppMessages.AUTHENTICATED_USER_REQUIRED);
        }
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.AUTHENTICATED_USER_NOT_FOUND));
    }

    public boolean canAccessFactory(String factoryCode) {
        return currentUser().canAccessFactory(factoryCode);
    }

    public void assertFactoryAccess(String factoryCode) {
        if (!canAccessFactory(factoryCode)) {
            throw new IllegalArgumentException(AppMessages.factoryAccessDenied(factoryCode));
        }
    }

    public boolean isUnrestricted() {
        User user = currentUser();
        return user.isAdminRole() || user.canManageSales();
    }

    /**
     * Strict Factory scope for Shipping Schedule / Carton Weight dispatch work.
     * Sales/Admin are intentionally unrestricted because Sales creates schedules for factories.
     * Every operational user must be explicitly assigned the schedule Factory (F1/F2/...).
     * Unlike the general LULULEMON workflow, a Shipping Schedule always has a Factory and
     * an empty Factory never grants visibility.
     */
    public boolean canAccessAssignedFactory(String factoryCode) {
        User user = currentUser();
        if (user.isAdminRole() || user.canManageSales()) return true;
        String normalized = User.normalizeFactoryCode(factoryCode);
        if (normalized.isEmpty()) return false;
        List<String> allowed = user.getFactoryPermissions();
        return allowed.contains("*") || allowed.contains(normalized);
    }

    public boolean canAccessWeighingFactory(String factoryCode) {
        return currentUser().canAccessWeighingFactory(factoryCode);
    }

    public void assertWeighingFactoryAccess(String factoryCode) {
        if (!canAccessWeighingFactory(factoryCode)) {
            throw new org.springframework.security.access.AccessDeniedException("Weighing Factory access denied: " + factoryCode);
        }
    }

    public void assertAssignedFactoryAccess(String factoryCode) {
        if (!canAccessAssignedFactory(factoryCode)) {
            throw new IllegalArgumentException(AppMessages.factoryAccessDenied(factoryCode));
        }
    }

    /** Empty list is unrestricted only when isUnrestricted() is true. */
    public List<String> accessibleFactories() {
        User user = currentUser();
        if (user.isAdminRole() || user.canManageSales()) return List.of();
        return user.getFactoryPermissions();
    }
}
