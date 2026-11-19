package org.bsl.cartonloading.dto.management;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PoInspectionProgressFinalTest {
    private static PurchaseOrder po(int planned) {
        PurchaseOrder value = new PurchaseOrder();
        value.setCartonCount(planned);
        value.setStatus("PACKING");
        return value;
    }

    private static Carton carton(String rfid, String weight) {
        Carton row = new Carton();
        row.setRfidStatus(rfid);
        row.setWeightStatus(weight);
        return row;
    }

    @Test void allPassIsFinalPassButNotEndedUntilExplicitEnd() {
        PoInspectionProgress p = PoInspectionProgress.fromCartons(po(2), List.of(
                carton(AppConstants.RfidCheck.PASS, AppConstants.WeightStatus.PASS),
                carton(AppConstants.RfidCheck.PASS, AppConstants.WeightStatus.PASS)));
        assertEquals("PASS", p.finalResult());
        assertEquals("COMPLETED", p.status());
        assertEquals(2, p.processedCartons());
        assertFalse(p.ended());
    }

    @Test void failureIsFinalFailedWithoutMisreportingSuccessfulCompletion() {
        PoInspectionProgress p = PoInspectionProgress.fromCartons(po(2), List.of(
                carton(AppConstants.RfidCheck.PASS, AppConstants.WeightStatus.PASS),
                carton(AppConstants.RfidCheck.FAILED, AppConstants.WeightStatus.PASS)));
        assertEquals("FAILED", p.finalResult());
        assertEquals("INCOMPLETE", p.status()); // unchanged PASS-only KPI
        assertEquals(2, p.processedCartons());
        assertFalse(p.ended());
    }

    @Test void generatedShortfallAndUnfinishedWeightCannotBeFinal() {
        assertEquals("PENDING", PoInspectionProgress.fromCartons(po(2), List.of(
                carton(AppConstants.RfidCheck.PASS, AppConstants.WeightStatus.PASS))).finalResult());
        PoInspectionProgress mixed = PoInspectionProgress.fromCartons(po(2), List.of(
                carton(AppConstants.RfidCheck.PASS, AppConstants.WeightStatus.PASS),
                carton(AppConstants.RfidCheck.FAILED, null)));
        assertEquals("PENDING", mixed.finalResult());
        assertEquals(1, mixed.processedCartons());
        PoInspectionProgress hold = PoInspectionProgress.fromCartons(po(1), List.of(
                carton(AppConstants.RfidCheck.FAILED, "HOLD")));
        assertEquals("PENDING", hold.finalResult());
    }

    @Test void onlyCurrentFinalCanBeShownAsEnded() {
        PurchaseOrder po = po(1);
        po.setCartonProcessingEndResult("FAILED");
        po.setCartonProcessingEndedAt(LocalDateTime.now());
        assertTrue(PoInspectionProgress.fromCartons(po, List.of(
                carton(AppConstants.RfidCheck.FAILED, AppConstants.WeightStatus.PASS))).ended());
        assertFalse(PoInspectionProgress.fromCartons(po, List.of(
                carton(AppConstants.RfidCheck.PASS, AppConstants.WeightStatus.PASS))).ended());
        assertFalse(PoInspectionProgress.fromCartons(po, List.of(
                carton(AppConstants.RfidCheck.FAILED, null))).ended());
    }
}
