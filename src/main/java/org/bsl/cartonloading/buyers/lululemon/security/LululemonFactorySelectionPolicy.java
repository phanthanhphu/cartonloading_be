package org.bsl.cartonloading.buyers.lululemon.security;

import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.bsl.cartonloading.shared.factory.FactorySelectionPolicy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Preserve existing LULULEMON Factory selection, including weighing-granted factories. */
@Component
public class LululemonFactorySelectionPolicy implements FactorySelectionPolicy {
    private final FactoryCatalogService catalog;
    private final BuyerFactoryAccessService access;

    public LululemonFactorySelectionPolicy(FactoryCatalogService catalog, BuyerFactoryAccessService access) {
        this.catalog = catalog;
        this.access = access;
    }

    @Override public String buyerCode() { return AppConstants.Buyer.LULULEMON; }

    @Override public void assertCanSelect(String factoryCode) {
        catalog.requireActive(AppConstants.Buyer.LULULEMON, factoryCode);
        if (!access.canAccessAssignedFactory(factoryCode) && !access.canAccessWeighingFactory(factoryCode)) {
            throw new AccessDeniedException("Selected Factory access denied: " + factoryCode);
        }
    }

    @Override public boolean isUnrestricted() { return access.isUnrestricted(); }
}
