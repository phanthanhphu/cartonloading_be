package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CartonScanProgressTest {
    private static Carton carton(int cartonNo, int planned, int scanned) {
        Carton c = new Carton();
        c.setCartonNo(cartonNo);
        c.setPlannedQty(planned);
        c.setScannedQty(scanned);
        return c;
    }

    private static Map<String, String> sourceRow(int qty, int pcsPerCarton) {
        return Map.of("Q'ty", Integer.toString(qty), "Pcs/ctn", Integer.toString(pcsPerCarton));
    }

    private static Carton identified(int cartonNo, int planned, int scanned) {
        Carton c = carton(cartonNo, planned, scanned);
        c.setSscc("0000000000000000" + cartonNo);
        c.setLabelConfirmedSku("LULULEMON-SKU");
        c.setLabelConfirmedAt(java.time.LocalDateTime.now());
        return c;
    }

    @Test
    void identifiedFullCartonCountsWithoutVerifiedItemScans() {
        PurchaseOrder po = new PurchaseOrder();
        po.setCartonCount(4);
        po.setQtyPerCarton(14);
        po.setAllBpRows(List.of(sourceRow(50, 14)));
        CartonScanProgress result = CartonScanProgress.from(po, List.of(
                identified(1, 14, 0), carton(2, 14, 0)));
        assertEquals(14, result.identifiedQty());
        assertEquals(1, result.identifiedCartons());
        assertEquals(1, result.identifiedFullCartons());
        assertEquals(0, result.scannedQty());
        assertEquals(0, result.scannedCartons());
    }

    @Test
    void onlySsccOrQtyConfirmationDoesNotPrematurelyCompleteIdentification() {
        PurchaseOrder po = new PurchaseOrder();
        po.setCartonCount(4);
        po.setQtyPerCarton(14);
        po.setAllBpRows(List.of(sourceRow(50, 14)));
        Carton onlySscc = carton(1, 14, 0);
        onlySscc.setSscc("000000000000000001");
        Carton noSscc = carton(2, 14, 0);
        noSscc.setLabelConfirmedAt(java.time.LocalDateTime.now());
        noSscc.setLabelConfirmedSku("LULU-SKU");
        CartonScanProgress result = CartonScanProgress.from(po, List.of(onlySscc, noSscc));
        assertEquals(0, result.identifiedQty());
        assertEquals(0, result.identifiedCartons());
    }

    @Test
    void oneIdentifiedOddCartonHasItsOwnPlannedItemCount() {
        PurchaseOrder po = new PurchaseOrder();
        po.setCartonCount(4);
        po.setQtyPerCarton(14);
        po.setAllBpRows(List.of(sourceRow(50, 14)));
        CartonScanProgress result = CartonScanProgress.from(po, List.of(
                identified(1, 14, 0), identified(4, 8, 0)));
        assertEquals(22, result.identifiedQty());
        assertEquals(2, result.identifiedCartons());
        assertEquals(1, result.identifiedFullCartons());
        assertEquals(8, result.identifiedRemainderQty());
        assertEquals(1, result.identifiedRemainderCartons());
        assertEquals(0, result.scannedQty());
    }

    @Test
    void scannedFullCartonsAndPartiallyScannedOddCartonAreCountedSeparately() {
        PurchaseOrder po = new PurchaseOrder();
        po.setCartonCount(4);
        po.setQtyPerCarton(14);
        po.setAllBpRows(List.of(sourceRow(50, 14)));
        po.setCartonPlannedQuantities(List.of(14, 14, 14, 8));
        CartonScanProgress result = CartonScanProgress.from(po, List.of(
                carton(1, 14, 14), carton(2, 14, 14), carton(3, 14, 0), carton(4, 8, 5)));

        assertEquals(33, result.scannedQty());
        assertEquals(2, result.scannedCartons());
        assertEquals(2, result.scannedFullCartons());
        assertEquals(5, result.scannedRemainderQty());
        assertEquals(0, result.scannedRemainderCartons());
    }

    @Test
    void oddCartonBecomesCompleteOnlyAfterAllRemainderItemsScanned() {
        PurchaseOrder po = new PurchaseOrder();
        po.setCartonCount(4);
        po.setQtyPerCarton(14);
        po.setAllBpRows(List.of(sourceRow(50, 14)));
        CartonScanProgress result = CartonScanProgress.from(po, List.of(carton(4, 8, 8)));
        assertEquals(8, result.scannedRemainderQty());
        assertEquals(1, result.scannedRemainderCartons());
        assertEquals(1, result.scannedCartons());
    }

    @Test
    void multipleAllBpRowsWithDifferentPcsPerCartonKeepCorrectOddClassification() {
        PurchaseOrder po = new PurchaseOrder();
        po.setCartonCount(4);
        po.setQtyPerCarton(30);
        po.setAllBpRows(List.of(sourceRow(32, 30), sourceRow(18, 14)));
        po.setCartonPlannedQuantities(List.of(30, 2, 14, 4));
        CartonScanProgress result = CartonScanProgress.from(po, List.of(
                carton(1, 30, 30), carton(2, 2, 2), carton(3, 14, 14), carton(4, 4, 3)));
        assertEquals(5, result.scannedRemainderQty());
        assertEquals(1, result.scannedRemainderCartons());
        assertEquals(2, result.scannedFullCartons());
    }
}
