package org.bsl.cartonloading.service.facade;

import org.bsl.cartonloading.buyers.lululemon.service.StationDeviceBindingService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.BuyerRequestScope;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class StationDeviceBindingFacade extends BuyerBoundService<StationDeviceBindingService> {
    public StationDeviceBindingFacade(BuyerRequestScope scope, StationDeviceBindingService service) {
        super(scope, Map.of(AppConstants.Buyer.LULULEMON, service));
    }
}
