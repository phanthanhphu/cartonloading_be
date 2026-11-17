package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.core.BuyerWorkspaceFactoryPolicy;
import org.bsl.cartonloading.buyers.core.BuyerWorkspaceFactoryPolicies;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.model.Factory;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.springframework.stereotype.Service;
import java.util.List;

/** Preserves the existing LULULEMON active/assigned Factory rules. */
@Service
public class LululemonWorkspaceFactoryPolicy implements BuyerWorkspaceFactoryPolicy {
    private final FactoryCatalogService factories;
    public LululemonWorkspaceFactoryPolicy(FactoryCatalogService factories) { this.factories = factories; }
    @Override public String buyerCode() { return AppConstants.Buyer.LULULEMON; }

    @Override public List<String> authorizedFactories(User user) {
        return factories.list(buyerCode(), false).stream()
                .map(Factory::getCode)
                .filter(user::canAccessFactory)
                .toList();
    }

    @Override public BuyerWorkspaceFactoryPolicies.SelectionIssue validateSelection(
            String selectedFactory, List<String> authorized) {
        if (selectedFactory != null && !selectedFactory.isEmpty() && !authorized.contains(selectedFactory))
            return new BuyerWorkspaceFactoryPolicies.SelectionIssue(403, "Factory is inactive or not assigned");
        if (!authorized.isEmpty() && (selectedFactory == null || selectedFactory.isEmpty()))
            return new BuyerWorkspaceFactoryPolicies.SelectionIssue(400, "Please select an active Factory");
        return null;
    }
}
