package org.bsl.cartonloading.config;

import org.bsl.cartonloading.buyers.es.model.CartonScanTransaction;
import org.bsl.cartonloading.buyers.es.model.FactoryBarcode;
import org.bsl.cartonloading.buyers.es.model.PackingAllocationLine;
import org.bsl.cartonloading.buyers.es.model.PackingListLine;
import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.bsl.cartonloading.buyers.es.model.Shipment;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuyerKeyBeforeConvertCallbackTest {
    private final BuyerKeyBeforeConvertCallback callback = new BuyerKeyBeforeConvertCallback();

    @Test void usesBuyerCodeForSharedOrder() {
        PackingOrder row = new PackingOrder();
        row.setBuyerCode("ES");
        callback.onBeforeConvert(row, AppConstants.Collection.ORDERS);
        assertEquals(AppConstants.Buyer.ENGELBERT_STRAUSS, row.getBuyerKey());
    }

    @Test void everyBuyerOwnedEsDocumentReceivesSameKey() {
        PackingListLine packing = new PackingListLine();
        PackingAllocationLine allocation = new PackingAllocationLine();
        CartonScanTransaction carton = new CartonScanTransaction();
        Shipment shipment = new Shipment();
        packing.setBuyerCode("engElbert_strauss");
        allocation.setBuyerCode("ES");
        carton.setBuyerCode("ENGELBERT_STRAUSS");
        shipment.setBuyerCode("ES");
        callback.onBeforeConvert(packing, AppConstants.Collection.PACKING_LIST_LINES);
        callback.onBeforeConvert(allocation, AppConstants.Collection.PACKING_ALLOCATION_LINES);
        callback.onBeforeConvert(carton, AppConstants.Collection.CARTON_OPERATIONS);
        callback.onBeforeConvert(shipment, AppConstants.Collection.SHIPMENTS);
        assertEquals(AppConstants.Buyer.ENGELBERT_STRAUSS, packing.getBuyerKey());
        assertEquals(AppConstants.Buyer.ENGELBERT_STRAUSS, allocation.getBuyerKey());
        assertEquals(AppConstants.Buyer.ENGELBERT_STRAUSS, carton.getBuyerKey());
        assertEquals(AppConstants.Buyer.ENGELBERT_STRAUSS, shipment.getBuyerKey());
    }

    @Test void cannotWriteBusinessDocumentWithoutBuyer() {
        PackingOrder order = new PackingOrder();
        assertThrows(IllegalArgumentException.class, () -> callback.onBeforeConvert(order, AppConstants.Collection.ORDERS));
    }

    @Test void unassignedFactoryBarcodeRemainsBuyerNeutral() {
        FactoryBarcode barcode = new FactoryBarcode();
        callback.onBeforeConvert(barcode, AppConstants.Collection.FACTORY_BARCODES);
        assertNull(barcode.getBuyerKey());
        barcode.setAssignedBuyerCode("ES");
        callback.onBeforeConvert(barcode, AppConstants.Collection.FACTORY_BARCODES);
        assertEquals(AppConstants.Buyer.ENGELBERT_STRAUSS, barcode.getBuyerKey());
        barcode.setAssignedBuyerCode(null);
        callback.onBeforeConvert(barcode, AppConstants.Collection.FACTORY_BARCODES);
        assertNull(barcode.getBuyerKey());
    }
}
