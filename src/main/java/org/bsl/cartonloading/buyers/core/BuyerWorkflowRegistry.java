package org.bsl.cartonloading.buyers.core;

import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.model.SystemType;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Autodiscovers installed Buyer workflow implementations using Spring beans. */
@Component
public class BuyerWorkflowRegistry {
    private final Map<String, Set<String>> implementations;

    public BuyerWorkflowRegistry(List<BuyerWorkflowModule> modules) {
        Map<String, Set<String>> resolved = new HashMap<>();
        for (BuyerWorkflowModule module : modules) {
            String code = BuyerAccess.normalize(module.buyerCode());
            if (code.isEmpty()) throw new IllegalStateException("Buyer workflow has no Buyer code");
            Set<String> types = Set.copyOf(module.implementedTypes());
            for (String type : types) {
                if (SystemType.normalize(type).isEmpty()) {
                    throw new IllegalStateException("Unsupported workflow Type for Buyer " + code + ": " + type);
                }
            }
            if (resolved.putIfAbsent(code, types) != null) {
                throw new IllegalStateException("Duplicate Buyer workflow implementation: " + code);
            }
        }
        this.implementations = Map.copyOf(resolved);
    }

    public boolean hasImplementedWorkflow(String buyerCode, String type) {
        String code = BuyerAccess.normalize(buyerCode);
        String normalizedType = SystemType.normalize(type);
        return !code.isEmpty() && !normalizedType.isEmpty()
                && implementations.getOrDefault(code, Set.of()).contains(normalizedType);
    }
}
