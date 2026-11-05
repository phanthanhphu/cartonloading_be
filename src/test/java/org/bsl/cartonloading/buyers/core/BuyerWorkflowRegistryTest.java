package org.bsl.cartonloading.buyers.core;

import org.bsl.cartonloading.buyers.es.EngelbertStraussWorkflowModule;
import org.bsl.cartonloading.buyers.lululemon.LululemonWorkflowModule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BuyerWorkflowRegistryTest {
    @Test
    void originalBuyerTypeMappingsArePreserved() {
        BuyerWorkflowRegistry registry = new BuyerWorkflowRegistry(List.of(
                new EngelbertStraussWorkflowModule(), new LululemonWorkflowModule()));

        assertTrue(registry.hasImplementedWorkflow("ES", "FACTORY_USE_ONLY"));
        assertTrue(registry.hasImplementedWorkflow("ENGELBERT_STRAUSS", "Factory Use Only"));
        assertTrue(registry.hasImplementedWorkflow("LULULEMON", "RFID"));
        assertFalse(registry.hasImplementedWorkflow("ES", "RFID"));
        assertFalse(registry.hasImplementedWorkflow("LULULEMON", "UNRFID"));
        assertFalse(registry.hasImplementedWorkflow("NEW_BUYER", "RFID"));
    }

    @Test
    void duplicatedBuyerModulesAreRejected() {
        assertThrows(IllegalStateException.class, () -> new BuyerWorkflowRegistry(List.of(
                new LululemonWorkflowModule(), new LululemonWorkflowModule())));
    }

    @Test
    void invalidSystemTypesAreRejected() {
        BuyerWorkflowModule broken = new BuyerWorkflowModule() {
            @Override public String buyerCode() { return "TEST"; }
            @Override public Set<String> implementedTypes() { return Set.of("UNKNOWN"); }
        };
        assertThrows(IllegalStateException.class, () -> new BuyerWorkflowRegistry(List.of(broken)));
    }
}
