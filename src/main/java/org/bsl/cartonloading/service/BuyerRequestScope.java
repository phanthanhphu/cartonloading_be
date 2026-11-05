package org.bsl.cartonloading.service;

import org.bsl.cartonloading.buyers.core.BuyerWorkflowRegistry;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.security.AccessControl;
import org.bsl.cartonloading.security.WorkspaceSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Verify Buyer identity against the JWT-authenticated workspace before dispatch. */
@Service
public class BuyerRequestScope {
    private final AccessControl accessControl;
    private final BuyerWorkflowRegistry workflows;

    public BuyerRequestScope(AccessControl accessControl, BuyerWorkflowRegistry workflows) {
        this.accessControl = accessControl;
        this.workflows = workflows;
    }

    public String requireVerifiedBuyer(String requestBuyerKey) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof WorkspaceSession session)) {
            throw new AccessDeniedException("Authenticated Buyer workspace is required");
        }
        String selected = BuyerAccess.normalize(session.buyer());
        if (selected.isEmpty()) {
            throw new AccessDeniedException("Authenticated Buyer is missing");
        }
        if (requestBuyerKey != null && !selected.equals(BuyerAccess.normalize(requestBuyerKey))) {
            throw new AccessDeniedException("Request Buyer does not match authenticated Buyer");
        }
        if (!accessControl.canAccessBuyer(selected)) {
            throw new AccessDeniedException("Buyer access denied: " + selected);
        }
        if (!workflows.hasImplementedWorkflow(selected, session.type())) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "Buyer workflow is not installed: " + selected);
        }
        return selected;
    }
}
