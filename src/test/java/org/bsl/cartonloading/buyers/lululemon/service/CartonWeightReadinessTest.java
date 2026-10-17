package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class CartonWeightReadinessTest {
    private static PurchaseOrder po() {
        PurchaseOrder po = new PurchaseOrder();
        po.setSku("000160019156");
        return po;
    }

    private static Carton identifiedCarton() {
        Carton carton = new Carton();
        carton.setSscc18("006285770391094841");
        carton.setPlannedQty(14);
        carton.setQtyConfirmedAt(LocalDateTime.now());
        carton.setLabelConfirmedAt(LocalDateTime.now());
        carton.setLabelConfirmedSku("160019156");
        carton.setStatus(AppConstants.CartonStatus.READY_TO_PACK);
        carton.setRfidStatus("WAITING_RFID");
        return carton;
    }

    @Test
    void identifiedCartonIsReadyBeforeWeightOrRfid() {
        Carton carton = identifiedCarton();
        assertNull(carton.getWeightStatus());
        assertEquals("WAITING_RFID", carton.getRfidStatus());
        assertTrue(CartonWeightReadiness.isReady(po(), carton));
    }

    @Test
    void neverTransferWithoutConfirmedLabelOrSscc() {
        Carton carton = identifiedCarton();
        carton.setLabelConfirmedAt(null);
        assertFalse(CartonWeightReadiness.isReady(po(), carton));
        carton.setLabelConfirmedAt(LocalDateTime.now());
        carton.setSscc18(null);
        assertFalse(CartonWeightReadiness.isReady(po(), carton));
    }

    @Test
    void wrongSkuAndEmptyQtyBlockTransfer() {
        Carton carton = identifiedCarton();
        carton.setLabelConfirmedSku("000160072027");
        assertFalse(CartonWeightReadiness.isReady(po(), carton));
        carton.setLabelConfirmedSku("160019156");
        carton.setPlannedQty(0);
        assertFalse(CartonWeightReadiness.isReady(po(), carton));
    }

    @Test
    void previouslyConfirmedLegacyLabelDoesNotNeedSeparateQtyTimestamp() {
        Carton carton = identifiedCarton();
        carton.setQtyConfirmedAt(null);
        assertTrue(CartonWeightReadiness.isReady(po(), carton));
    }
}
