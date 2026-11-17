package org.bsl.cartonloading.buyers.lululemon.service.facade;

import org.bsl.cartonloading.buyers.lululemon.service.ManagementReportService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.BuyerRequestScope;
import org.bsl.cartonloading.service.facade.BuyerBoundService;
import org.springframework.stereotype.Service;

/** Dispatches the legacy LULULEMON report endpoint to an authorized Buyer implementation. */
@Service
public class ManagementReportFacade extends BuyerBoundService<ManagementReportService> {
    public ManagementReportFacade(BuyerRequestScope scope, ManagementReportService implementation) {
        super(scope, java.util.Map.of(AppConstants.Buyer.LULULEMON, implementation));
    }
}
