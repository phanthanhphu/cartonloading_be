package org.bsl.cartonloading.security;

import org.bsl.cartonloading.model.BuyerTypePermission;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShippingScheduleCreateAccessTest {
    private final UserRepository users = mock(UserRepository.class);
    private final AccessControl access = new AccessControl(users);

    private User user(String... permissions) {
        User user = new User();
        user.setRole("USER");
        user.setEnabled(true);
        user.setEmail("creator@example.com");
        user.setBuyerPermissions(List.of("LULULEMON"));
        user.setBuyerTypePermissions(List.of(new BuyerTypePermission("LULULEMON", List.of("RFID"))));
        user.setBuyerAccessPermissions(Map.of("LULULEMON", List.of(permissions)));
        user.setFactoryPermissions(List.of("F1"));
        when(users.findByEmail("creator@example.com")).thenReturn(Optional.of(user));
        return user;
    }

    private void session(String buyer, String type) {
        var auth = new UsernamePasswordAuthenticationToken("creator@example.com", "secret", List.of());
        auth.setDetails(new WorkspaceSession(buyer, type));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void independentCreateGrantAndRevocation() {
        User creator = user("SHIPPING_SCHEDULE_CREATE");
        session("LULULEMON", "RFID");
        assertTrue(access.canCreateShippingSchedule());
        assertFalse(access.canManageShipping());
        assertFalse(access.canReopenShippingSchedule());
        creator.setBuyerAccessPermissions(Map.of("LULULEMON", List.of("SHIPPING_SCHEDULER")));
        assertFalse(access.canCreateShippingSchedule());
    }

    @Test void disabledWrongBuyerAndWrongTypeCannotCreate() {
        User creator = user("SHIPPING_SCHEDULE_CREATE");
        session("LULULEMON", "OTHER");
        assertFalse(access.canCreateShippingSchedule());
        session("ENGELBERT_STRAUSS", "FACTORY_USE_ONLY");
        assertFalse(access.canCreateShippingSchedule());
        session("LULULEMON", "RFID");
        creator.setEnabled(false);
        assertFalse(access.canCreateShippingSchedule());
    }

    @Test void adminRetainsCreatePrivilege() {
        User admin = user();
        admin.setRole("ADMIN");
        session("LULULEMON", "RFID");
        assertTrue(access.canCreateShippingSchedule());
    }
}
