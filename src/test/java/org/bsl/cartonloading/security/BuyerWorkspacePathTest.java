package org.bsl.cartonloading.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class BuyerWorkspacePathTest {
    @Test
    void llSessionRejectsEsApis() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/buyers/engelbert-strauss/orders");
        assertFalse(JwtAuthenticationFilter.matchesRequestWorkspace(request, "LULULEMON", "RFID"));
        request.setRequestURI("/api/carton-loading/engelbert-strauss/orders/123/cartons");
        assertFalse(JwtAuthenticationFilter.matchesRequestWorkspace(request, "LULULEMON", "RFID"));
    }
    @Test
    void queryBuyerCannotOverrideJwtSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/order-management/orders");
        request.setParameter("buyer", "ENGELBERT_STRAUSS");
        assertFalse(JwtAuthenticationFilter.matchesRequestWorkspace(request, "LULULEMON", "RFID"));
        request.setParameter("buyer", "LULULEMON");
        assertTrue(JwtAuthenticationFilter.matchesRequestWorkspace(request, "LULULEMON", "RFID"));
    }
}
