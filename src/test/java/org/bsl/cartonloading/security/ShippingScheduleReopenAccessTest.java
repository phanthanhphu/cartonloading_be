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

class ShippingScheduleReopenAccessTest {
    private final UserRepository users = mock(UserRepository.class);
    private final AccessControl access = new AccessControl(users);

    private User user(String... permissions) {
        User user = new User();
        user.setRole("USER");
        user.setEnabled(true);
        user.setEmail("supervisor@example.com");
        user.setBuyerPermissions(List.of("LULULEMON"));
        user.setBuyerTypePermissions(List.of(new BuyerTypePermission("LULULEMON", List.of("RFID"))));
        user.setBuyerAccessPermissions(Map.of("LULULEMON", List.of(permissions)));
        user.setFactoryPermissions(List.of("F1"));
        when(users.findByEmail("supervisor@example.com")).thenReturn(Optional.of(user));
        return user;
    }

    private void session(String buyer, String type) {
        var auth = new UsernamePasswordAuthenticationToken("supervisor@example.com", "secret", List.of());
        auth.setDetails(new WorkspaceSession(buyer, type));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void onlyExplicitReopenGrantCanUnsubmit() {
        User user = user("SHIPPING_SCHEDULE_REOPEN");
        session("LULULEMON", "RFID");
        assertTrue(access.canReopenShippingSchedule());
        assertFalse(user.canLululemonShippingScheduler());
        user.setBuyerAccessPermissions(Map.of("LULULEMON", List.of("SHIPPING_SCHEDULER")));
        assertFalse(access.canReopenShippingSchedule());
    }

    @Test void disabledAndWrongWorkspaceCannotUnsubmit() {
        User user = user("SHIPPING_SCHEDULE_REOPEN");
        session("ENGELBERT_STRAUSS", "FACTORY_USE_ONLY");
        assertFalse(access.canReopenShippingSchedule());
        session("LULULEMON", "RFID");
        user.setEnabled(false);
        assertFalse(access.canReopenShippingSchedule());
    }

    @Test void unassignedSystemTypeCannotUnsubmit() {
        user("SHIPPING_SCHEDULE_REOPEN");
        session("LULULEMON", "OTHER");
        assertFalse(access.canReopenShippingSchedule());
    }

    @Test void adminRetainsUnsubmitPrivilege() {
        User admin = user();
        admin.setRole("ADMIN");
        session("LULULEMON", "RFID");
        assertTrue(access.canReopenShippingSchedule());
    }
}
