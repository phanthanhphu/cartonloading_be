package org.bsl.cartonloading.shared.orders.service;

import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.constants.AppMessages;
import org.bsl.cartonloading.common.exception.WorkflowNotFoundException;
import org.bsl.cartonloading.common.exception.WorkflowValidationException;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.shared.orders.model.OrderDocument;
import org.bsl.cartonloading.shared.orders.repository.BuyerOrderRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Buyer-neutral Order reader.  Lookup remains scoped by buyerCode, and LULULEMON
 * retains its original factory and weighing access rules.  Future Buyer access
 * policies belong behind this service, never inside another Buyer's module.
 * Read-only; the ES Packing Order writer keeps its current API and validation.
 */
@Service
public class BuyerOrderLookupService {
    private final BuyerOrderRepository repository;
    private final BuyerFactoryAccessService factoryAccess;

    public BuyerOrderLookupService(BuyerOrderRepository repository, BuyerFactoryAccessService factoryAccess) {
        this.repository = repository;
        this.factoryAccess = factoryAccess;
    }

    public OrderDocument getEntity(String buyerCode, String id) {
        String buyer = BuyerAccess.normalize(buyerCode);
        if (buyer.isEmpty()) throw new WorkflowValidationException(AppMessages.unsupportedBuyer(buyerCode));
        OrderDocument order = repository.findByIdAndBuyerCode(id, buyer)
                .orElseThrow(() -> new WorkflowNotFoundException(AppMessages.ORDER_NOT_FOUND));
        if (AppConstants.Buyer.LULULEMON.equals(buyer)) {
            if (order.getFactoryCode() == null || order.getFactoryCode().isBlank()) {
                if (!factoryAccess.isUnrestricted()) {
                    throw new AccessDeniedException("Order has no Factory. Ask Admin to classify it.");
                }
            } else if (!factoryAccess.canAccessAssignedFactory(order.getFactoryCode())
                    && !(factoryAccess.currentUser().canWeightCheck()
                         && factoryAccess.canAccessWeighingFactory(order.getFactoryCode()))) {
                throw new AccessDeniedException("Order Factory access denied: " + order.getFactoryCode());
            }
        }
        return order;
    }
}
