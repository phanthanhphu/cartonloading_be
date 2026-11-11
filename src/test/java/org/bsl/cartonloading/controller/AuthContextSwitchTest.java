package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.buyers.core.BuyerWorkflowRegistry;
import org.bsl.cartonloading.model.Buyer;
import org.bsl.cartonloading.model.Factory;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.security.JwtUtil;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.service.BuyerService;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.bsl.cartonloading.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthContextSwitchTest {
    private final UserService users = mock(UserService.class);
    private final JwtUtil jwt = mock(JwtUtil.class);
    private final BuyerService buyers = mock(BuyerService.class);
    private final BuyerWorkflowRegistry workflows = mock(BuyerWorkflowRegistry.class);
    private final FactoryCatalogService factories = mock(FactoryCatalogService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AuthController controller = new AuthController(users, jwt,
            mock(PasswordEncoder.class), buyers, workflows, factories, audit);

    private UsernamePasswordAuthenticationToken admin() {
        User user = new User();
        user.setEmail("admin@example.com");
        user.setRole("ADMIN");
        user.setEnabled(true);
        when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(user));
        return new UsernamePasswordAuthenticationToken("admin@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private void onlyLululemonIsImplemented() {
        Buyer buyer = new Buyer();
        buyer.setBuyerKey("LULULEMON");
        buyer.setBuyerName("LULULEMON");
        buyer.setSlug("lululemon");
        buyer.setActive(true);
        buyer.setSupportedTypes(List.of("RFID", "UNRFID"));
        when(buyers.loginOptions()).thenReturn(List.of(buyer));
        when(workflows.hasImplementedWorkflow("LULULEMON", "RFID")).thenReturn(true);
        Factory factory = new Factory();
        factory.setCode("F3");
        factory.setActive(true);
        when(factories.list("LULULEMON", false)).thenReturn(List.of(factory));
    }

    @Test
    void optionsExposeOnlyImplementedApprovedTypesAndActiveFactories() {
        var authentication = admin();
        onlyLululemonIsImplemented();
        ResponseEntity<?> response = controller.contextOptions(authentication);
        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        var options = (List<AuthController.ContextOption>) ((Map<?, ?>) response.getBody()).get("options");
        assertEquals(1, options.size());
        assertEquals(List.of("RFID"), options.get(0).supportedTypes());
        assertEquals(List.of("F3"), options.get(0).factories());
    }

    @Test
    void rejectsUnsupportedTypeAndUnassignedFactoryWithoutIssuingToken() {
        var authentication = admin();
        onlyLululemonIsImplemented();
        var request = new AuthController.SwitchContextRequest();
        request.setBuyerCode("LULULEMON");
        request.setSystemType("UNRFID");
        request.setFactoryCode("F3");
        assertEquals(403, controller.switchContext(request, authentication).getStatusCode().value());
        request.setSystemType("RFID");
        request.setFactoryCode("F9");
        assertEquals(403, controller.switchContext(request, authentication).getStatusCode().value());
        verifyNoInteractions(jwt);
    }

    @Test
    void acceptedContextGetsServerIssuedJwt() {
        var authentication = admin();
        onlyLululemonIsImplemented();
        when(jwt.generateToken(eq("admin@example.com"), eq("ADMIN"), anyLong(),
                anyCollection(), anyCollection(), eq("LULULEMON"), eq("RFID")))
                .thenReturn("signed.test.token");
        var request = new AuthController.SwitchContextRequest();
        request.setBuyerCode("LULULEMON");
        request.setSystemType("RFID");
        request.setFactoryCode("F3");
        ResponseEntity<?> response = controller.switchContext(request, authentication);
        assertEquals(200, response.getStatusCode().value());
        var body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals("signed.test.token", body.get("token"));
        assertEquals("F3", body.get("selectedFactory"));
    }
}
