package org.bsl.cartonloading.buyers.es.security;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.model.BuyerAccess;
import org.springframework.stereotype.Component;

@Component("barcodeWorkflowBuyerGuard")
public class BuyerGuard {
    public boolean matches(String buyer) {
        return AppConstants.Buyer.ENGELBERT_STRAUSS.equals(BuyerAccess.normalize(buyer));
    }
}
