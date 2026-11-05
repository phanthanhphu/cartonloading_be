package org.bsl.cartonloading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsl.cartonloading.model.AuditLog;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.AuditLogRepository;
import org.bsl.cartonloading.repository.UserRepository;
import org.bsl.cartonloading.security.WorkspaceSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Audit must remain Factory-scoped at the API, not just in the table filter. */
class AuditLogFactoryScopeTest {
    private final AuditLogRepository records = mock(AuditLogRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final AuditLogService subject = new AuditLogService(records, users, mongo, new ObjectMapper());

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    private void signIn(boolean admin, List<String> assigned) {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("person@example.test");
        when(auth.getDetails()).thenReturn(new WorkspaceSession("LULULEMON", "RFID"));
        SecurityContextHolder.getContext().setAuthentication(auth);
        User user = mock(User.class);
        when(user.isEnabled()).thenReturn(true);
        when(user.canAccessBuyerType("LULULEMON", "RFID")).thenReturn(true);
        when(user.isAdminRole()).thenReturn(admin);
        if (!admin) when(user.getFactoryPermissions()).thenReturn(assigned);
        when(users.findByEmail("person@example.test")).thenReturn(Optional.of(user));
        when(mongo.find(any(Query.class), eq(AuditLog.class))).thenReturn(List.of());
    }

    private void search(String factory) {
        subject.search(null, null, null, null, null, null, factory, null, null, null, 0, 20);
    }

    @Test void f3OperatorDefaultsToF3AndNeverReadsMissingFactory() {
        signIn(false, List.of("F3"));
        search(null);
        var captured = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongo).count(captured.capture(), eq(AuditLog.class));
        String query = captured.getValue().getQueryObject().toJson();
        assertTrue(query.contains("factoryCode"));
        assertTrue(query.contains("F3"));
        assertTrue(query.contains("buyerCode"));
        assertTrue(query.contains("LULULEMON"));
        assertFalse(query.contains("$in"));
    }

    @Test void f3OperatorCannotQueryF2() {
        signIn(false, List.of("F3"));
        assertThrows(AccessDeniedException.class, () -> search("F2"));
        verifyNoInteractions(mongo);
    }

    @Test void multiFactoryOperatorMustChooseOneFactoryNotAll() {
        signIn(false, List.of("F3", "F4"));
        var error = assertThrows(ResponseStatusException.class, () -> search(null));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(mongo);
        search("F3");
        var captured = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongo).count(captured.capture(), eq(AuditLog.class));
        assertTrue(captured.getValue().getQueryObject().toJson().contains("F3"));
    }

    @Test void f3OperatorCannotOpenF2OrMissingFactoryDetail() {
        signIn(false, List.of("F3"));
        AuditLog f2Log = new AuditLog();
        f2Log.setBuyerCode("LULULEMON"); f2Log.setFactoryCode("F2");
        when(records.findById("some-id")).thenReturn(Optional.of(f2Log));
        assertThrows(AccessDeniedException.class, () -> subject.getById("some-id", null));
        assertThrows(AccessDeniedException.class, () -> subject.getById("some-id", "F2"));
        f2Log.setFactoryCode(null);
        assertThrows(AccessDeniedException.class, () -> subject.getById("some-id", "F3"));
    }

    @Test void adminWithoutFiltersSeesAllIncludingUnscopedLegacyLogs() {
        signIn(true, List.of());
        search(null);
        var captured = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongo).count(captured.capture(), eq(AuditLog.class));
        assertTrue(captured.getValue().getQueryObject().isEmpty());
    }
}
