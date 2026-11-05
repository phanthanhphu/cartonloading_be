package org.bsl.cartonloading.service.facade;

import org.bsl.cartonloading.buyers.lululemon.dto.CartonWeightHistoryRow;
import org.bsl.cartonloading.buyers.lululemon.service.WeighingService;
import org.bsl.cartonloading.common.factory.FactoryCodes;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.BuyerRequestScope;
import org.bsl.cartonloading.service.SelectedFactoryScope;
import org.springframework.stereotype.Service;

/** Shared service entry point: dispatch is verified server-side, never guessed by controllers. */
@Service
public class WeighingFacade extends BuyerBoundService<WeighingService> {
    private final SelectedFactoryScope selectedFactoryScope;

    public WeighingFacade(BuyerRequestScope buyerScope,
                          WeighingService implementation,
                          SelectedFactoryScope selectedFactoryScope) {
        super(buyerScope, java.util.Map.of(AppConstants.Buyer.LULULEMON, implementation));
        this.selectedFactoryScope = selectedFactoryScope;
    }

    public java.util.List<CartonWeightHistoryRow> stationHistoryForFactory(
            String buyer, String requestedFactory) {
        return forBuyer(buyer, impl -> {
            String factory = selectedFactoryScope.constrain(AppConstants.Buyer.LULULEMON, requestedFactory);
            java.util.List<CartonWeightHistoryRow> accessible = impl.stationHistory(buyer);
            if (factory == null || factory.isBlank()) return accessible; // legacy API
            String validated = FactoryCodes.requireValid(factory);
            return accessible.stream().filter(row -> validated.equalsIgnoreCase(row.factoryCode())).toList();
        });
    }
}
