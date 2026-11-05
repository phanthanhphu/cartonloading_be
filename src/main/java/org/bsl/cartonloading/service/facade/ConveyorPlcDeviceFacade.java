package org.bsl.cartonloading.service.facade;

import org.bsl.cartonloading.buyers.lululemon.service.ConveyorPlcDeviceService;
import org.bsl.cartonloading.service.BuyerRequestScope;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.stereotype.Service;

@Service
public class ConveyorPlcDeviceFacade extends BuyerBoundService<ConveyorPlcDeviceService> {
    public ConveyorPlcDeviceFacade(BuyerRequestScope scope,
        ConveyorPlcDeviceService impl) {
        super(scope, java.util.Map.of(AppConstants.Buyer.LULULEMON, impl));
    }
}
