package org.bsl.cartonloading.service;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.CartonItem;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.dto.management.ItemManagementRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class OrderManagementItemRowTest {
    @Test
    void cartonLabelSkuIsShownOnItemsWithoutPretendingEachItemWasBarcodeScanned() {
        Carton carton = new Carton();
        LocalDateTime scannedAt = LocalDateTime.of(2026, 10, 21, 14, 30);
        carton.setLabelConfirmedSku("000158570717");
        carton.setLabelConfirmedBy("packing-operator");
        carton.setLabelConfirmedAt(scannedAt);
        CartonItem item = new CartonItem();
        item.setExpectedSku("000158570717");
        item.setStatus("IDENTIFIED");
        PurchaseOrder po = new PurchaseOrder();
        po.setStyleNumber("LU9E15S031166");

        ItemManagementRow row = OrderManagementService.cartonItemRow(item, po, carton);

        assertEquals("000158570717", row.sku());
        assertEquals("packing-operator", row.scannedBy());
        assertEquals(scannedAt, row.scannedAt());
        assertEquals("IDENTIFIED", row.status());
        assertEquals("LU9E15S031166", row.style());
        assertNull(item.getScannedSku(), "One carton label confirmation must not be treated as an item barcode scan");
    }

    @Test
    void pendingOrUnassignedItemDoesNotInheritUnverifiedPoSku() {
        CartonItem item = new CartonItem();
        item.setStatus("PENDING");
        PurchaseOrder po = new PurchaseOrder();
        po.setSku("000158570717");
        ItemManagementRow row = OrderManagementService.cartonItemRow(item, po, new Carton());
        assertNull(row.sku());
        assertNull(row.scannedBy());
        assertNull(row.scannedAt());
    }

    @Test
    void realItemBarcodeScanTakesPrecedenceOverCartonLabelAttribution() {
        LocalDateTime scanTime = LocalDateTime.of(2026, 10, 21, 15, 5);
        CartonItem item = new CartonItem();
        item.setExpectedSku("000158570717");
        item.setScannedSku("000158570717");
        item.setScannedBy("barcode-operator");
        item.setScannedAt(scanTime);
        Carton carton = new Carton();
        carton.setLabelConfirmedSku("000158570717");
        carton.setLabelConfirmedBy("packing-operator");
        carton.setLabelConfirmedAt(scanTime.minusHours(1));
        ItemManagementRow row = OrderManagementService.cartonItemRow(item, null, carton);
        assertEquals("000158570717", row.sku());
        assertEquals("barcode-operator", row.scannedBy());
        assertEquals(scanTime, row.scannedAt());
    }
}
