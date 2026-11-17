package org.bsl.cartonloading.buyers.core;

import org.bsl.cartonloading.model.User;
import java.util.List;

/** Optional Buyer-specific Factory choices and selection validation for workspace switching. */
public interface BuyerWorkspaceFactoryPolicy {
    String buyerCode();
    List<String> authorizedFactories(User user);
    BuyerWorkspaceFactoryPolicies.SelectionIssue validateSelection(String factoryCode, List<String> approvedFactories);
}
