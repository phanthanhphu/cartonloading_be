package org.bsl.cartonloading.buyers.lululemon.service.facade;
import org.bsl.cartonloading.buyers.lululemon.service.ScalePlcOperationsService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.BuyerRequestScope;
import org.bsl.cartonloading.service.facade.BuyerBoundService;
import org.springframework.stereotype.Service;
/** Routes a verified buyer only to its implementation; never silently falls back. */
@Service
public class ScalePlcOperationsFacade extends BuyerBoundService<ScalePlcOperationsService> {
    public ScalePlcOperationsFacade(BuyerRequestScope scope, ScalePlcOperationsService implementation) {
        super(scope, java.util.Map.of(AppConstants.Buyer.LULULEMON, implementation));
    }
}
