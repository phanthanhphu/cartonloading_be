package org.bsl.cartonloading.buyers.core;

import org.bsl.cartonloading.common.constants.AppMessages;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.bsl.cartonloading.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BuyerFactoryAccessService {
    private final UserRepository userRepository;
    private final FactoryCatalogService factories;

    public BuyerFactoryAccessService(UserRepository userRepository, FactoryCatalogService factories) {
        this.userRepository = userRepository;
        this.factories = factories;
    }

    public User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new IllegalArgumentException(AppMessages.AUTHENTICATED_USER_REQUIRED);
        }
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.AUTHENTICATED_USER_NOT_FOUND));
    }

    private boolean activeFactory(String factoryCode) {
        try { factories.requireActive(AppConstants.Buyer.LULULEMON, factoryCode); return true; }
        catch (IllegalArgumentException e) { return false; }
    }

    public boolean canAccessFactory(String factoryCode) {
        return currentUser().isAdminRole() || (activeFactory(factoryCode) && currentUser().canAccessFactory(factoryCode));
    }

    public void assertFactoryAccess(String factoryCode) {
        if (!canAccessFactory(factoryCode)) {
            throw new IllegalArgumentException(AppMessages.factoryAccessDenied(factoryCode));
        }
    }

    public boolean isUnrestricted() {
        User user = currentUser();
        return user.isAdminRole();
    }

    /**
     * Strict Factory scope for Shipping Schedule / Carton Weight dispatch work.
     * Admin alone is unrestricted. SALES is still scoped to assigned Factories.
     * Every operational user must be explicitly assigned the schedule Factory (F1/F2/...).
     * Unlike the general LULULEMON workflow, a Shipping Schedule always has a Factory and
     * an empty Factory never grants visibility.
     */
    public boolean canAccessAssignedFactory(String factoryCode) {
        User user = currentUser();
        if (user.isAdminRole()) return true;
        String normalized = User.normalizeFactoryCode(factoryCode);
        if (normalized.isEmpty() || !activeFactory(normalized)) return false;
        List<String> allowed = user.getFactoryPermissions();
        return allowed.contains(normalized);
    }

    public boolean canAccessWeighingFactory(String factoryCode) {
        return currentUser().isAdminRole() || (activeFactory(factoryCode) && currentUser().canAccessWeighingFactory(factoryCode));
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
        if (user.isAdminRole()) return List.of();
        return user.getFactoryPermissions().stream().filter(this::activeFactory).toList();
    }
}
