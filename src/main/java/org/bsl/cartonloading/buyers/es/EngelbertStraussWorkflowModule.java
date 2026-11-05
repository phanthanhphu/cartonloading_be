package org.bsl.cartonloading.buyers.es;

import org.bsl.cartonloading.buyers.core.BuyerWorkflowModule;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.model.SystemType;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Registers the existing ENGELBERT_STRAUSS workflow without changing its processing logic. */
@Component
public class EngelbertStraussWorkflowModule implements BuyerWorkflowModule {
    @Override public String buyerCode() { return AppConstants.Buyer.ENGELBERT_STRAUSS; }
    @Override public Set<String> implementedTypes() { return Set.of(SystemType.FACTORY_USE_ONLY); }
}
