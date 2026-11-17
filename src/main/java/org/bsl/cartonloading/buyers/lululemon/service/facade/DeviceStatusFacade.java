package org.bsl.cartonloading.buyers.lululemon.service.facade;

import org.bsl.cartonloading.buyers.lululemon.service.DeviceStatusSnapshotService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.BuyerRequestScope;
import org.bsl.cartonloading.service.facade.BuyerBoundService;
import org.springframework.stereotype.Service;

@Service
public class DeviceStatusFacade extends BuyerBoundService<DeviceStatusSnapshotService> {
    public DeviceStatusFacade(BuyerRequestScope scope, DeviceStatusSnapshotService implementation) {
        super(scope, java.util.Map.of(AppConstants.Buyer.LULULEMON, implementation));
    }
}
