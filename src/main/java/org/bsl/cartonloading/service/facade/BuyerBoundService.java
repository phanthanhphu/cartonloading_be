package org.bsl.cartonloading.service.facade;

import org.bsl.cartonloading.service.BuyerRequestScope;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Non-fallback Buyer dispatch. Modules may contribute additional implementations of the same contract. */
public abstract class BuyerBoundService<I> {
    private final BuyerRequestScope buyerScope;
    private final Map<String, I> implementations;

    protected BuyerBoundService(BuyerRequestScope buyerScope, Map<String, I> implementations) {
        this.buyerScope = buyerScope;
        this.implementations = Map.copyOf(implementations);
    }

    @FunctionalInterface
    public interface BuyerOperation<I, R, E extends Exception> {
        R apply(I implementation) throws E;
    }

    public <R, E extends Exception> R forBuyer(String requestBuyerKey, BuyerOperation<I, R, E> operation) throws E {
        String selected = buyerScope.requireVerifiedBuyer(requestBuyerKey);
        I implementation = implementations.get(selected);
        if (implementation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "Buyer " + selected + " does not implement this workflow");
        }
        return operation.apply(implementation);
    }
}
