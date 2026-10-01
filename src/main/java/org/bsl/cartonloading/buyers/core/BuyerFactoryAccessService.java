package org.bsl.cartonloading.buyers.core;

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
            throw new IllegalArgumentException("Authenticated user is required");
        }
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException("Authenticated user no longer exists"));
    }

    public boolean canAccessFactory(String factoryCode) {
        return currentUser().canAccessFactory(factoryCode);
    }

    public void assertFactoryAccess(String factoryCode) {
        if (!canAccessFactory(factoryCode)) {
            throw new IllegalArgumentException("You do not have permission to access factory " + factoryCode);
        }
    }

    public boolean isUnrestricted() {
        User user = currentUser();
        return user.isAdminRole() || user.canManageSales();
    }

    /** Empty list is unrestricted only when isUnrestricted() is true. */
    public List<String> accessibleFactories() {
        User user = currentUser();
        if (user.isAdminRole() || user.canManageSales()) return List.of();
        return user.getFactoryPermissions();
    }
}
