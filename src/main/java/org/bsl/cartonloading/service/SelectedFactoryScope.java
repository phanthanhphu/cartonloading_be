package org.bsl.cartonloading.service;

import jakarta.servlet.http.HttpServletRequest;
import org.bsl.cartonloading.shared.factory.FactorySelectionPolicy;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Factory selected in the UI is a request filter, not an authorization grant.
 * Resolve it on every request; the server rechecks active Factory and user permissions.
 * Legacy API clients without the header retain their previous authorized views.
 */
@Service
public class SelectedFactoryScope {
    public static final String HEADER = AppConstants.HttpHeader.ACTIVE_FACTORY;
    private static final String VERIFIED_FACTORY_ATTRIBUTE = SelectedFactoryScope.class.getName() + ".verifiedFactory";
    private final Map<String, FactorySelectionPolicy> policies;

    public SelectedFactoryScope(List<FactorySelectionPolicy> factorySelectionPolicies) {
        Map<String, FactorySelectionPolicy> byBuyer = new HashMap<>();
        for (FactorySelectionPolicy policy : factorySelectionPolicies) {
            String buyer = BuyerAccess.normalize(policy.buyerCode());
            if (buyer.isBlank() || byBuyer.putIfAbsent(buyer, policy) != null) {
                throw new IllegalStateException("Invalid or duplicate Factory selection policy: " + buyer);
            }
        }
        this.policies = Map.copyOf(byBuyer);
    }

    public String requested(String buyerCode) {
        String buyer = BuyerAccess.normalize(buyerCode);
        FactorySelectionPolicy policy = policies.get(buyer);
        if (policy == null) return null;
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        HttpServletRequest request = attributes.getRequest();
        String raw = request.getHeader(HEADER);
        if (raw == null || raw.isBlank()) return null;
        String code = raw.trim().toUpperCase(Locale.ROOT);
        // This service is used inside row filters. Verify once per HTTP request, not
        // once per PO/Carton, to avoid repeated user and MongoDB lookups.
        if (code.equals(request.getAttribute(VERIFIED_FACTORY_ATTRIBUTE + "." + buyer))) return code;
        // Factory must exist AND be active. Admin may select any active Factory;
        // operational accounts may select only assigned/weighing-permitted factories.
        policy.assertCanSelect(code);
        request.setAttribute(VERIFIED_FACTORY_ATTRIBUTE + "." + buyer, code);
        return code;
    }

    public String constrain(String buyerCode, String requestFactory) {
        String selected = requested(buyerCode);
        if (selected == null) return requestFactory;
        // Keep the existing explicitly invoked Admin-only UNASSIGNED classification view.
        FactorySelectionPolicy policy = policies.get(BuyerAccess.normalize(buyerCode));
        if ("UNASSIGNED".equalsIgnoreCase(requestFactory) && policy != null && policy.isUnrestricted()) return requestFactory;
        if (requestFactory != null && !requestFactory.isBlank()
                && !selected.equalsIgnoreCase(requestFactory.trim())) {
            throw new AccessDeniedException("Requested Factory differs from selected Factory: " + selected);
        }
        return selected;
    }

    public boolean matches(String selected, String recordFactory) {
        return selected == null || selected.equalsIgnoreCase(recordFactory == null ? "" : recordFactory.trim());
    }

    public void assertMatches(String selected, String recordFactory) {
        if (!matches(selected, recordFactory))
            throw new AccessDeniedException("Record belongs to another Factory");
    }
}
