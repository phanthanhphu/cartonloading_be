package org.bsl.cartonloading.config;

import org.bsl.cartonloading.buyers.es.model.CartonScanTransaction;
import org.bsl.cartonloading.buyers.es.model.FactoryBarcode;
import org.bsl.cartonloading.buyers.es.model.PackingAllocationLine;
import org.bsl.cartonloading.buyers.es.model.PackingListLine;
import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.bsl.cartonloading.buyers.es.model.Shipment;
import org.bsl.cartonloading.model.BuyerAccess;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.stereotype.Component;

/** Keep buyer identity persisted consistently in all shared buyer-owned collections. */
@Component
public class BuyerKeyBeforeConvertCallback implements BeforeConvertCallback<Object> {
    @Override
    public Object onBeforeConvert(Object entity, String collection) {
        if (entity instanceof PackingOrder row) row.setBuyerKey(key(row.getBuyerCode()));
        else if (entity instanceof PackingListLine row) row.setBuyerKey(key(row.getBuyerCode()));
        else if (entity instanceof PackingAllocationLine row) row.setBuyerKey(key(row.getBuyerCode()));
        else if (entity instanceof CartonScanTransaction row) row.setBuyerKey(key(row.getBuyerCode()));
        else if (entity instanceof Shipment row) row.setBuyerKey(key(row.getBuyerCode()));
        else if (entity instanceof FactoryBarcode row) row.setBuyerKey(
                row.getAssignedBuyerCode() == null || row.getAssignedBuyerCode().isBlank()
                        ? null : key(row.getAssignedBuyerCode()));
        return entity;
    }

    private static String key(String buyerCode) {
        String normalized = BuyerAccess.normalize(buyerCode);
        if (normalized.isBlank()) throw new IllegalArgumentException("buyerCode is required to save buyer-owned data");
        return normalized;
    }
}
