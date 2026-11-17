package org.bsl.cartonloading.shared.orders.service;

import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.shared.orders.model.OrderDocument;
import org.bsl.cartonloading.shared.orders.repository.BuyerOrderRepository;
import org.bsl.cartonloading.common.exception.WorkflowNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Regression protection while migrating shared Order reads away from ES-owned services. */
class BuyerOrderLookupServiceTest {
    private final BuyerOrderRepository repository = mock(BuyerOrderRepository.class);
    private final BuyerFactoryAccessService factoryAccess = mock(BuyerFactoryAccessService.class);
    private final BuyerOrderLookupService service = new BuyerOrderLookupService(repository, factoryAccess);

    @Test void lookupIsScopedToBuyer() {
        when(repository.findByIdAndBuyerCode("one", "LULULEMON")).thenReturn(Optional.empty());
        assertThrows(WorkflowNotFoundException.class, () -> service.getEntity("LULULEMON", "one"));
        verify(repository).findByIdAndBuyerCode("one", "LULULEMON");
        verify(repository, never()).findById("one");
    }

    @Test void lululemonOrderNeedsFactoryPermission() {
        OrderDocument row = new OrderDocument();
        row.setFactoryCode("F3");
        when(repository.findByIdAndBuyerCode("one", "LULULEMON")).thenReturn(Optional.of(row));
        User user = mock(User.class);
        when(factoryAccess.currentUser()).thenReturn(user);
        assertThrows(AccessDeniedException.class, () -> service.getEntity("LULULEMON", "one"));
    }

    @Test void assignedLululemonFactoryMayRead() {
        OrderDocument row = new OrderDocument();
        row.setFactoryCode("F3");
        when(repository.findByIdAndBuyerCode("one", "LULULEMON")).thenReturn(Optional.of(row));
        when(factoryAccess.canAccessAssignedFactory("F3")).thenReturn(true);
        assertSame(row, service.getEntity("LULULEMON", "one"));
    }

    @Test void unassignedLululemonOrdersRequireAdmin() {
        OrderDocument row = new OrderDocument();
        when(repository.findByIdAndBuyerCode("one", "LULULEMON")).thenReturn(Optional.of(row));
        assertThrows(AccessDeniedException.class, () -> service.getEntity("LULULEMON", "one"));
        when(factoryAccess.isUnrestricted()).thenReturn(true);
        assertSame(row, service.getEntity("LULULEMON", "one"));
    }

    @Test void otherBuyerDoesNotInheritLululemonFactoryPolicy() {
        OrderDocument row = new OrderDocument();
        when(repository.findByIdAndBuyerCode("one", "ENGELBERT_STRAUSS")).thenReturn(Optional.of(row));
        assertSame(row, service.getEntity("ENGELBERT_STRAUSS", "one"));
        verifyNoInteractions(factoryAccess);
    }
}
