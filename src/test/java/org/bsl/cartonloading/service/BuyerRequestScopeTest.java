package org.bsl.cartonloading.service;

import org.bsl.cartonloading.buyers.core.BuyerWorkflowRegistry;
import org.bsl.cartonloading.buyers.es.EngelbertStraussWorkflowModule;
import org.bsl.cartonloading.buyers.lululemon.LululemonWorkflowModule;
import org.bsl.cartonloading.security.AccessControl;
import org.bsl.cartonloading.security.WorkspaceSession;
import org.bsl.cartonloading.service.facade.BuyerBoundService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuyerRequestScopeTest {
    private final AccessControl permissions = new AccessControl(null) {
        @Override public boolean canAccessBuyer(String buyerCode) { return true; }
    };
    private final BuyerWorkflowRegistry workflows = new BuyerWorkflowRegistry(List.of(
            new EngelbertStraussWorkflowModule(), new LululemonWorkflowModule()));
    private final BuyerRequestScope scope = new BuyerRequestScope(permissions, workflows);

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private static void login(String buyer, String type) {
        var auth = new UsernamePasswordAuthenticationToken("unit@test", null, List.of());
        auth.setDetails(new WorkspaceSession(buyer, type));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test void requestBuyerMustMatchJwtWorkspace() {
        login("ENGELBERT_STRAUSS", "FACTORY_USE_ONLY");
        assertEquals("ENGELBERT_STRAUSS", scope.requireVerifiedBuyer("ES"));
        assertEquals("ENGELBERT_STRAUSS", scope.requireVerifiedBuyer(null));
        assertThrows(AccessDeniedException.class, () -> scope.requireVerifiedBuyer("LULULEMON"));
    }

    @Test void noAuthenticatedWorkspaceMeansNoDispatch() {
        assertThrows(AccessDeniedException.class, () -> scope.requireVerifiedBuyer("ES"));
    }

    @Test void installedWorkflowIsMandatory() {
        login("UNKNOWN_BUYER", "RFID");
        assertThrows(ResponseStatusException.class,
                () -> scope.requireVerifiedBuyer("UNKNOWN_BUYER"));
    }

    @Test void serviceUsesVerifiedBuyerNotFallback() {
        login("ENGELBERT_STRAUSS", "FACTORY_USE_ONLY");
        BuyerBoundService<String> service = new BuyerBoundService<>(scope, Map.of(
                "ENGELBERT_STRAUSS", "es-engine", "LULULEMON", "lululemon-engine")) { };
        assertEquals("es-engine", service.forBuyer("ES", value -> value));
        assertThrows(AccessDeniedException.class,
                () -> service.forBuyer("LULULEMON", value -> value));
        BuyerBoundService<String> esOnly = new BuyerBoundService<>(scope,
                Map.of("LULULEMON", "lululemon-engine")) { };
        assertThrows(ResponseStatusException.class,
                () -> esOnly.forBuyer("ES", value -> value));
    }
}
