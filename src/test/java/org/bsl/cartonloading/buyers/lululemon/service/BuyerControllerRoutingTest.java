package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.service.facade.ConveyorPlcOperationsFacade;
import org.bsl.cartonloading.service.BuyerRequestScope;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** New HTTP adapters must never silently route a different Buyer to LULULEMON's implementation. */
class BuyerControllerRoutingTest {
    @Test void rejectsBuyerWithNoDeviceOperationsImplementation() {
        BuyerRequestScope scope = mock(BuyerRequestScope.class);
        ConveyorPlcOperationsService impl = mock(ConveyorPlcOperationsService.class);
        when(scope.requireVerifiedBuyer("ES")).thenReturn("ES");
        ConveyorPlcOperationsFacade facade = new ConveyorPlcOperationsFacade(scope, impl);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> facade.forBuyer("ES", svc -> svc.list("ES")));
        assertEquals(HttpStatus.NOT_IMPLEMENTED, error.getStatusCode());
        verifyNoInteractions(impl);
    }

    @Test void routesOnlyInstalledBuyerImplementation() {
        BuyerRequestScope scope = mock(BuyerRequestScope.class);
        ConveyorPlcOperationsService impl = mock(ConveyorPlcOperationsService.class);
        when(scope.requireVerifiedBuyer("LULULEMON")).thenReturn("LULULEMON");
        when(impl.list("LULULEMON")).thenReturn(java.util.List.of());
        ConveyorPlcOperationsFacade facade = new ConveyorPlcOperationsFacade(scope, impl);
        assertEquals(java.util.List.of(), facade.forBuyer("LULULEMON", svc -> svc.list("LULULEMON")));
        verify(impl).list("LULULEMON");
    }
}
