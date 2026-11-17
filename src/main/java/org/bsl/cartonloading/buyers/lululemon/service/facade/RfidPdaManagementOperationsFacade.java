package org.bsl.cartonloading.buyers.lululemon.service.facade;
import org.bsl.cartonloading.buyers.lululemon.service.RfidPdaManagementOperationsService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.BuyerRequestScope;
import org.bsl.cartonloading.service.facade.BuyerBoundService;
import org.springframework.stereotype.Service;
/** Routes a verified buyer only to its implementation; never silently falls back. */
@Service
public class RfidPdaManagementOperationsFacade extends BuyerBoundService<RfidPdaManagementOperationsService> {
    public RfidPdaManagementOperationsFacade(BuyerRequestScope scope, RfidPdaManagementOperationsService implementation) {
        super(scope, java.util.Map.of(AppConstants.Buyer.LULULEMON, implementation));
    }
}
