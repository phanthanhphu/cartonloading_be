package org.bsl.cartonloading.buyers.core;

import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.model.User;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Buyer-neutral dispatcher; no fallback to another Buyer's Factory catalog. */
@Service
public class BuyerWorkspaceFactoryPolicies {
    private final Map<String, BuyerWorkspaceFactoryPolicy> policies;

    public record SelectionIssue(int status, String message) { }

    public BuyerWorkspaceFactoryPolicies(List<BuyerWorkspaceFactoryPolicy> modules) {
        this.policies = Map.copyOf(modules.stream().collect(Collectors.toMap(
                policy -> BuyerAccess.normalize(policy.buyerCode()),
                policy -> policy,
                (a, b) -> { throw new IllegalStateException("Duplicate Buyer Factory context policy"); })));
    }

    public List<String> authorizedFactories(String buyerCode, User user) {
        BuyerWorkspaceFactoryPolicy policy = policies.get(BuyerAccess.normalize(buyerCode));
        return policy == null ? List.of() : policy.authorizedFactories(user);
    }

    public SelectionIssue validateSelection(String buyerCode, String factoryCode, List<String> authorized) {
        BuyerWorkspaceFactoryPolicy policy = policies.get(BuyerAccess.normalize(buyerCode));
        if (policy == null) {
            return factoryCode == null || factoryCode.isEmpty() ? null
                    : new SelectionIssue(400, "Factory catalog is not available for this Buyer");
        }
        return policy.validateSelection(factoryCode, authorized);
    }
}
