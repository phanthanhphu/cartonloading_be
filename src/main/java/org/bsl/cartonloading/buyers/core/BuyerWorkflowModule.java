package org.bsl.cartonloading.buyers.core;

import java.util.Set;

/**
 * Declares which Buyer/Type pairs have an actual implemented workflow.
 * A record in Buyer Master is not sufficient to activate an operational module.
 * Business services remain inside the corresponding Buyer package.
 */
public interface BuyerWorkflowModule {
    String buyerCode();
    Set<String> implementedTypes();
}
