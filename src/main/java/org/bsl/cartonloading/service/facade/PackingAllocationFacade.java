package org.bsl.cartonloading.service.facade;

import org.bsl.cartonloading.buyers.es.service.PackingAllocationService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.BuyerRequestScope;
import org.springframework.stereotype.Service;

/** Shared service entry point: dispatch is verified server-side, never guessed by controllers. */
@Service
public class PackingAllocationFacade extends BuyerBoundService<PackingAllocationService> {
    public PackingAllocationFacade(BuyerRequestScope buyerScope, PackingAllocationService implementation) {
        super(buyerScope, java.util.Map.of(AppConstants.Buyer.ENGELBERT_STRAUSS, implementation));
    }
}
