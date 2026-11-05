package org.bsl.cartonloading.service.facade;

import org.bsl.cartonloading.buyers.lululemon.service.PoShippingPlanService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.BuyerRequestScope;
import org.springframework.stereotype.Service;

/** Shared service entry point: dispatch is verified server-side, never guessed by controllers. */
@Service
public class PoShippingPlanFacade extends BuyerBoundService<PoShippingPlanService> {
    public PoShippingPlanFacade(BuyerRequestScope buyerScope, PoShippingPlanService implementation) {
        super(buyerScope, java.util.Map.of(AppConstants.Buyer.LULULEMON, implementation));
    }
}
