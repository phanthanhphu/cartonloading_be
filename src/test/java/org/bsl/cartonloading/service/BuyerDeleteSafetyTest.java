package org.bsl.cartonloading.service;

import org.bsl.cartonloading.model.Buyer;
import org.bsl.cartonloading.repository.BuyerRepository;
import org.bsl.cartonloading.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuyerDeleteSafetyTest {
    @Test void linkedBuyerReturns409AndIsNotDeleted() {
        BuyerRepository repo = mock(BuyerRepository.class);
        UserRepository users = mock(UserRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        BuyerUsageService usage = mock(BuyerUsageService.class);
        Buyer buyer = new Buyer();
        buyer.setId("6afac8046cbd9146d8211c60");
        buyer.setBuyerKey("LULULEMON");
        when(repo.findById(buyer.getId())).thenReturn(Optional.of(buyer));
        when(users.findAll()).thenReturn(List.of());
        when(usage.findUsage("LULULEMON")).thenReturn(List.of("Factories", "Purchase Orders"));
        BuyerService service = new BuyerService(repo, users, audit, usage);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.delete(buyer.getId()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Purchase Orders"));
        verify(repo, never()).delete(any(Buyer.class));
        verifyNoInteractions(audit);
    }

    @Test void unusedBuyerMayBeDeleted() {
        BuyerRepository repo = mock(BuyerRepository.class);
        UserRepository users = mock(UserRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        BuyerUsageService usage = mock(BuyerUsageService.class);
        Buyer buyer = new Buyer();
        buyer.setId("6afac8046cbd9146d8211c60");
        buyer.setBuyerKey("DWD");
        when(repo.findById(buyer.getId())).thenReturn(Optional.of(buyer));
        when(users.findAll()).thenReturn(List.of());
        when(usage.findUsage("DWD")).thenReturn(List.of());
        BuyerService service = new BuyerService(repo, users, audit, usage);
        assertDoesNotThrow(() -> service.delete(buyer.getId()));
        verify(repo).delete(buyer);
    }
}
