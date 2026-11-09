package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests: undo of new carton label may free only its own provisional PO SKU. */
class ReassignSkuSafetyTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 7, 8, 0);

    private PurchaseOrder po() {
        PurchaseOrder p = new PurchaseOrder();
        p.setId("po1");
        p.setSku("000158570717");
        p.setSkuAssignedBy("user1");
        p.setSkuAssignedAt(NOW);
        return p;
    }

    private Carton carton(String id) {
        Carton c = new Carton();
        c.setId(id);
        c.setStatus(AppConstants.CartonStatus.READY_TO_PACK);
        return c;
    }

    private Carton justIdentified() {
        Carton c = carton("carton1");
        c.setLabelConfirmedSku("000158570717");
        c.setLabelConfirmedBy("user1");
        c.setLabelConfirmedAt(NOW.plusSeconds(3));
        return c;
    }

    @Test void allowsUndoOfOnlyCartonWhichClaimedPoSku() {
        Carton identified = justIdentified();
        assertTrue(WorkflowService.mayReleaseProvisionalPoSku(po(), identified,
                List.of(identified, carton("carton2"))));
    }

    @Test void keepsImportedPoSku() {
        PurchaseOrder imported = po();
        imported.setSkuAssignedAt(null);
        assertFalse(WorkflowService.mayReleaseProvisionalPoSku(imported, justIdentified(),
                List.of(justIdentified())));
    }

    @Test void keepsSkuUsedByAnotherCarton() {
        Carton other = carton("carton2");
        other.setLabelConfirmedSku("000158570717");
        assertFalse(WorkflowService.mayReleaseProvisionalPoSku(po(), justIdentified(),
                List.of(justIdentified(), other)));
    }

    @Test void keepsSkuWhenAnotherCartonWasScanned() {
        Carton other = carton("carton2");
        other.setScannedQty(1);
        assertFalse(WorkflowService.mayReleaseProvisionalPoSku(po(), justIdentified(),
                List.of(justIdentified(), other)));
    }

    @Test void keepsPoSkuClaimedByAnotherActor() {
        PurchaseOrder p = po();
        p.setSkuAssignedBy("someoneElse");
        assertFalse(WorkflowService.mayReleaseProvisionalPoSku(p, justIdentified(),
                List.of(justIdentified())));
    }

    @Test void keepsPoSkuClaimedBeforeCartonIdentification() {
        PurchaseOrder p = po();
        p.setSkuAssignedAt(NOW.minusHours(1));
        assertFalse(WorkflowService.mayReleaseProvisionalPoSku(p, justIdentified(),
                List.of(justIdentified())));
    }
}
