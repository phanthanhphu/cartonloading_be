package org.bsl.cartonloading.service;

import org.bsl.cartonloading.common.socket.AppSocketPublisher;
import org.bsl.cartonloading.dto.admin.UserAdminRequest;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.BuyerRepository;
import org.bsl.cartonloading.repository.DepartmentRepository;
import org.bsl.cartonloading.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LastEnabledAdminSafetyTest {
    @Test void cannotDisableTheOnlyEnabledAdmin() {
        UserRepository users = mock(UserRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        UserAdminService service = new UserAdminService(users, mock(DepartmentRepository.class),
                mock(BuyerRepository.class), mock(PasswordEncoder.class), mock(UserService.class),
                audit, mock(FactoryCatalogService.class), mock(AppSocketPublisher.class));
        User admin = new User();
        admin.setId("admin-1");
        admin.setRole("ADMIN");
        admin.setEnabled(true);
        admin.setEmail("admin@example.test");
        when(users.findById("admin-1")).thenReturn(Optional.of(admin));
        when(users.findByEmailIgnoreCase("admin@example.test")).thenReturn(Optional.of(admin));
        when(users.findAll()).thenReturn(List.of(admin));
        when(audit.snapshot(admin)).thenReturn(Map.of());
        UserAdminRequest disable = new UserAdminRequest("Administrator", "admin@example.test", null,
                null, null, "ADMIN", false, null, List.of(), List.of(),
                List.of(), List.of(), List.of(), Map.of());
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.update("admin-1", disable));
        assertTrue(exception.getMessage().contains("last enabled Administrator"));
        verify(users, never()).save(any(User.class));
    }
}
