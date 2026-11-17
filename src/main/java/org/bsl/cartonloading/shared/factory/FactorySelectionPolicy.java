package org.bsl.cartonloading.shared.factory;

/** Buyer module supplies validation of a selected Factory; policy lookup must never grant access by itself. */
public interface FactorySelectionPolicy {
    String buyerCode();
    void assertCanSelect(String factoryCode);
    boolean isUnrestricted();
}
