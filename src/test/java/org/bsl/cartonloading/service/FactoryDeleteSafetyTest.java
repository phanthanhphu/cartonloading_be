package org.bsl.cartonloading.service;

import org.bsl.cartonloading.common.socket.AppSocketPublisher;
import org.bsl.cartonloading.model.Factory;
import org.bsl.cartonloading.repository.FactoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FactoryDeleteSafetyTest {
    private Factory factory() {
        Factory f = new Factory();
        f.setId("f-id");
        f.setCode("F3");
        f.setName("Factory Three");
        f.setActive(true);
        return f;
    }

    @Test void blocksDeleteWhenAssignedToUserOrUsedByOrder() {
        FactoryRepository repo = mock(FactoryRepository.class);
        FactoryUsageService usage = mock(FactoryUsageService.class);
        AuditLogService audit = mock(AuditLogService.class);
        AppSocketPublisher socket = mock(AppSocketPublisher.class);
        when(repo.findById("f-id")).thenReturn(Optional.of(factory()));
        when(usage.findUsage("F3")).thenReturn(List.of("Users / Factory assignments", "Orders"));
        FactoryCatalogService subject = new FactoryCatalogService(repo, audit, socket, usage);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> subject.delete("f-id"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Orders"));
        verify(repo, never()).delete(any(Factory.class));
        verifyNoInteractions(audit, socket);
    }

    @Test void deletesUnusedFactoryWithoutCascadingAndEmitsAudit() {
        FactoryRepository repo = mock(FactoryRepository.class);
        FactoryUsageService usage = mock(FactoryUsageService.class);
        AuditLogService audit = mock(AuditLogService.class);
        AppSocketPublisher socket = mock(AppSocketPublisher.class);
        Factory f = factory();
        when(repo.findById("f-id")).thenReturn(Optional.of(f));
        when(usage.findUsage("F3")).thenReturn(List.of());
        when(audit.snapshot(f)).thenReturn(Map.of("code", "F3"));
        FactoryCatalogService subject = new FactoryCatalogService(repo, audit, socket, usage);
        assertDoesNotThrow(() -> subject.delete("f-id"));
        verify(repo).delete(f);
        verify(audit).logChange(anyString(), eq("DELETE"), eq("FACTORY"), eq("f-id"),
                anyString(), eq("LULULEMON"), eq("F3"), any(), isNull(), isNull(), isNull());
        verify(socket).organizationChanged(eq("FACTORY"), eq("DELETE"));
    }

    @Test void missingFactoryReturnsNotFound() {
        FactoryRepository repo = mock(FactoryRepository.class);
        FactoryCatalogService subject = new FactoryCatalogService(repo, mock(AuditLogService.class),
                mock(AppSocketPublisher.class), mock(FactoryUsageService.class));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> subject.delete("missing"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(repo, never()).delete(any(Factory.class));
    }
}
