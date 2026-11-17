package org.bsl.cartonloading.service;

import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.model.Factory;
import org.bsl.cartonloading.security.AccessControl;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.List;

/** Authorized shared Factory master query; keeps Buyer-specific visibility out of HTTP controllers. */
@Service
public class FactoryAccessQueryService {
    private final FactoryCatalogService catalog;
    private final AccessControl control;
    private final BuyerFactoryAccessService access;
    public FactoryAccessQueryService(FactoryCatalogService catalog, AccessControl control, BuyerFactoryAccessService access) {
        this.catalog = catalog; this.control = control; this.access = access;
    }
    public List<Factory> list(String buyerKey, boolean includeInactive) {
        // Preserve previous permission, inactive and assigned/weighing Factory behavior exactly.
        if (!control.isAdmin() && !control.canAccessBuyer(buyerKey)) throw new AccessDeniedException("Buyer access denied");
        if (control.isAdmin()) return catalog.list(buyerKey, includeInactive);
        return catalog.list(buyerKey, false).stream()
                .filter(factory -> access.canAccessAssignedFactory(factory.getCode())
                        || (access.currentUser().canLululemonScanWeight() && access.canAccessWeighingFactory(factory.getCode()))).toList();
    }
}
