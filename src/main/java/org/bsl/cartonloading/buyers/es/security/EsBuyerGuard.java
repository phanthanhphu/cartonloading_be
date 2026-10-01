package org.bsl.cartonloading.buyers.es.security;

import org.bsl.cartonloading.model.BuyerAccess;
import org.springframework.stereotype.Component;

@Component("esBuyerGuard")
public class EsBuyerGuard {
    public boolean matches(String buyer) {
        return BuyerAccess.ENGELBERT_STRAUSS.equals(BuyerAccess.normalize(buyer));
    }
}
