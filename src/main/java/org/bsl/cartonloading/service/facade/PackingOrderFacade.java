package org.bsl.cartonloading.service.facade;

import org.bsl.cartonloading.buyers.es.service.PackingOrderService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.BuyerRequestScope;
import org.springframework.stereotype.Service;

/** Shared service entry point: dispatch is verified server-side, never guessed by controllers. */
@Service
public class PackingOrderFacade extends BuyerBoundService<PackingOrderService> {
    public PackingOrderFacade(BuyerRequestScope buyerScope, PackingOrderService implementation) {
        super(buyerScope, java.util.Map.of(AppConstants.Buyer.ENGELBERT_STRAUSS, implementation));
    }
}
