package org.bsl.cartonloading.buyers.lululemon;

import org.bsl.cartonloading.buyers.core.BuyerWorkflowModule;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.model.SystemType;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Registers the existing LULULEMON workflow without changing its processing logic. */
@Component
public class LululemonWorkflowModule implements BuyerWorkflowModule {
    @Override public String buyerCode() { return AppConstants.Buyer.LULULEMON; }
    @Override public Set<String> implementedTypes() { return Set.of(SystemType.RFID); }
}
